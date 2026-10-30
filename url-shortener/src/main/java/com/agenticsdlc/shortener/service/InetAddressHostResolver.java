package com.agenticsdlc.shortener.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link HostResolver}: the platform resolver ({@link InetAddress#getAllByName}) with a time limit.
 *
 * <p>{@code getAllByName} has no timeout of its own and cannot be interrupted, so lookups run on a small bounded pool
 * ({@value #DEFAULT_THREADS} threads, {@value #DEFAULT_QUEUE_CAPACITY} queued lookups) and the caller waits at most
 * {@code shortener.resolve-timeout}. A lookup that is still running when the caller gives up keeps its thread until
 * the operating system answers; when all threads are stuck and the queue is full, new lookups are refused at once.
 * Both cases surface as {@link HostResolver.ResolutionTimeoutException} (the validator then rejects the URL), so a
 * slow or hostile DNS server can neither block request threads beyond the timeout nor grow the pool without bound.
 */
public final class InetAddressHostResolver implements HostResolver, AutoCloseable {

    static final int DEFAULT_THREADS = 8;
    static final int DEFAULT_QUEUE_CAPACITY = 64;

    private final Lookup lookup;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;

    /**
     * Creates a resolver backed by {@link InetAddress#getAllByName}.
     *
     * @param timeout maximum time to wait for one lookup
     */
    public InetAddressHostResolver(Duration timeout) {
        this(InetAddress::getAllByName, timeout, DEFAULT_THREADS, DEFAULT_QUEUE_CAPACITY);
    }

    InetAddressHostResolver(Lookup lookup, Duration timeout, int threads, int queueCapacity) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.lookup = lookup;
        this.timeout = timeout;
        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemonThreads(), new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        Future<InetAddress[]> pending;
        try {
            pending = executor.submit(() -> lookup.lookup(host));
        } catch (RejectedExecutionException saturated) {
            throw new ResolutionTimeoutException(host + ": resolver saturated");
        }
        try {
            InetAddress[] addresses = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException(host + ": no addresses");
            }
            return List.of(addresses);
        } catch (TimeoutException slow) {
            pending.cancel(true);
            throw new ResolutionTimeoutException(host + ": lookup timed out after " + timeout.toMillis() + " ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new ResolutionTimeoutException(host + ": lookup interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof UnknownHostException unknown) {
                throw unknown;
            }
            UnknownHostException wrapped = new UnknownHostException(host + ": lookup failed");
            wrapped.initCause(failed.getCause());
            throw wrapped;
        }
    }

    /** {@return lookups waiting for a free thread} */
    int queuedLookups() {
        return executor.getQueue().size();
    }

    /** Stops the lookup threads (invoked by Spring on shutdown). */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(Objects.requireNonNull(task), "host-resolver-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** The blocking lookup, {@link InetAddress#getAllByName} in production. */
    @FunctionalInterface
    interface Lookup {

        /**
         * Looks up a host.
         *
         * @param host host name
         * @return its addresses
         * @throws UnknownHostException when the name does not resolve
         */
        InetAddress[] lookup(String host) throws UnknownHostException;
    }
}
