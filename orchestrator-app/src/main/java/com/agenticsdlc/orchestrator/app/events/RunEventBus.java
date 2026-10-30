package com.agenticsdlc.orchestrator.app.events;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process bus of the run changes committed by <em>this</em> node: the JPA run store hands every committed change to
 * {@link #committed(RunCommitted)} right after the transaction commits, and the bus delivers it to its subscribers
 * (the server-sent event streams of the console, the run and stage observations) on its own dispatcher thread, in
 * commit order.
 *
 * <p>Publishing never blocks the engine: changes wait in a bounded queue ({@value #DEFAULT_CAPACITY} by default) and
 * a change that does not fit is dropped and counted ({@link #dropped()}); subscribers that must not miss state (the
 * event streams) recover by reading the store again. A subscriber that throws is logged and does not affect the
 * others.</p>
 *
 * <p>The bus only sees commits of this process. In a multi-node deployment, changes that another node commits (for
 * runs that node is executing) never reach this bus; consumers must not treat the absence of an event as the absence
 * of a change.</p>
 */
public final class RunEventBus implements RunCommitListener, AutoCloseable {

    /**
     * Default capacity of the dispatch queue. Every queued change holds a run snapshot, so the queue is kept short;
     * the dispatcher normally empties it within milliseconds.
     */
    public static final int DEFAULT_CAPACITY = 1_000;

    private static final Logger LOG = LoggerFactory.getLogger(RunEventBus.class);

    private final List<Consumer<RunCommitted>> subscribers = new CopyOnWriteArrayList<>();
    private final Executor dispatcher;
    private final @Nullable ExecutorService ownedDispatcher;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    /**
     * Creates a bus with its own dispatcher thread and a queue of {@value #DEFAULT_CAPACITY} changes.
     */
    public RunEventBus() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a bus with its own dispatcher thread.
     *
     * @param capacity maximum number of changes waiting for delivery (at least 1)
     */
    public RunEventBus(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().name("sdlc-run-events").daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.dispatcher = executor;
        this.ownedDispatcher = executor;
    }

    /**
     * Creates a bus that delivers on the given executor; it must run tasks one at a time in submission order (tests
     * pass {@code Runnable::run} for synchronous delivery).
     *
     * @param dispatcher the executor
     */
    public RunEventBus(Executor dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.ownedDispatcher = null;
    }

    /**
     * Subscribes to committed changes.
     *
     * @param subscriber called on the dispatcher thread for every change, in commit order; must not block for long
     * @return a handle that unsubscribes when closed
     */
    public Subscription subscribe(Consumer<RunCommitted> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /**
     * Queues a committed change for delivery. Never blocks and never throws.
     *
     * @param committed the change
     */
    @Override
    public void committed(RunCommitted committed) {
        if (closed || subscribers.isEmpty()) {
            return;
        }
        try {
            dispatcher.execute(() -> deliver(committed));
        } catch (RejectedExecutionException e) {
            if (closed) {
                return;
            }
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                LOG.warn("run event bus is full; change dropped runId={} droppedTotal={}", committed.runId(), count);
            }
        }
    }

    private void deliver(RunCommitted committed) {
        for (Consumer<RunCommitted> subscriber : subscribers) {
            try {
                subscriber.accept(committed);
            } catch (RuntimeException e) {
                LOG.warn("run event subscriber failed runId={} error={}", committed.runId(), e.toString());
            }
        }
    }

    /**
     * Returns the number of changes dropped because the queue was full.
     *
     * @return dropped changes since start
     */
    public long dropped() {
        return dropped.get();
    }

    /**
     * Returns the number of subscribers.
     *
     * @return subscribers
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Stops delivering; changes still queued are discarded.
     */
    @Override
    public void close() {
        closed = true;
        if (ownedDispatcher != null) {
            ownedDispatcher.shutdownNow();
        }
    }

    /**
     * A subscription; closing it unsubscribes.
     */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {

        /**
         * Unsubscribes.
         */
        @Override
        void close();
    }
}
