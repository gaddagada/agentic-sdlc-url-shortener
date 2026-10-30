package com.agenticsdlc.orchestrator.app.api;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.agenticsdlc.orchestrator.app.api.dto.AuditEventView;
import com.agenticsdlc.orchestrator.app.api.dto.RunView;
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Serves the server-sent event streams of the console: per run ({@code run} and {@code audit} events) and of the
 * runs list ({@code run-summary} events), plus a {@code heartbeat} event on every stream at a fixed interval.
 *
 * <p><strong>Source of the events.</strong> The hub subscribes to the {@link RunEventBus}, which carries every run
 * change <em>this node</em> commits (after the commit). A stream therefore reflects the commits of this node only: in
 * a multi-node deployment a run executing on another node changes without an event here, which is why the console
 * also polls (runs whose {@code nodeId} differs from this node's id, and a slow safety poll for every stream).</p>
 *
 * <p><strong>Run streams.</strong> On connect the stream sends the run's audit events after {@code Last-Event-ID}
 * (all of them without one) as {@code audit} events whose SSE id is the event's sequence number, then the current
 * {@code run} view. After that each committed change sends its new audit events and then the new run view; a client
 * that reconnects with the last id it saw receives exactly the events it missed. Live changes that arrive while the
 * initial state is being read are buffered and de-duplicated by sequence number and run version. When the run is
 * terminal (COMPLETED or ABORTED) and its events are sent, the stream ends with that final {@code run} event.</p>
 *
 * <p><strong>Resources.</strong> At most {@code sdlc.console.max-sse-connections} streams are open at a time (more
 * are refused with {@link StreamLimitException}, 503); beyond its initial backlog (at most {@value #MAX_BACKLOG} audit
 * events), every stream buffers at most {@value #MAX_QUEUED_EVENTS} unsent live events (a stream that falls further
 * behind is closed and the client reconnects with {@code Last-Event-ID}); writes run on a bounded
 * executor, at most one write task per stream; streams end after {@code sdlc.console.sse-timeout} and on errors, and
 * every end releases the stream's slot. Ending a stream never waits for its client: a stream ended while a write to
 * its client is blocked is completed by its writer, and keeps its slot, until that write returns.</p>
 */
@Component
public class RunStreamHub implements DisposableBean {

    /** Event name of a run view. */
    public static final String RUN_EVENT = "run";

    /** Event name of an audit event. */
    public static final String AUDIT_EVENT = "audit";

    /** Event name of a run list item. */
    public static final String SUMMARY_EVENT = "run-summary";

    /** Event name of the keep-alive. */
    public static final String HEARTBEAT_EVENT = "heartbeat";

    /** Maximum number of unsent events per stream. */
    static final int MAX_QUEUED_EVENTS = 1_000;

    /** Page size of the audit backlog read on connect. */
    static final int AUDIT_PAGE = 1_000;

    /** Maximum number of backlog events sent on connect (older ones are available from the audit endpoint). */
    static final int MAX_BACKLOG = 20_000;

    private static final Logger LOG = LoggerFactory.getLogger(RunStreamHub.class);

    private final OrchestrationEngine engine;
    private final AuditSink auditSink;
    private final RunViewMapper mapper;
    private final WorkflowGraph graph;
    private final JsonCodec codec;
    private final Executor sendExecutor;
    private final @Nullable BoundedSendExecutor ownedExecutor;
    private final Clock clock;
    private final Settings settings;
    private final LongFunction<SseEmitter> emitters;
    private final AtomicInteger connections = new AtomicInteger();
    private final ConcurrentMap<String, Set<RunStream>> runStreams = new ConcurrentHashMap<>();
    private final Set<ListStream> listStreams = ConcurrentHashMap.newKeySet();
    private final RunEventBus.Subscription subscription;
    private final @Nullable ScheduledFuture<?> heartbeat;

    /**
     * Limits of the hub.
     *
     * @param maxConnections maximum concurrent streams
     * @param heartbeatInterval interval of the heartbeat event
     * @param timeout lifetime of a stream
     */
    public record Settings(int maxConnections, Duration heartbeatInterval, Duration timeout) {

        /**
         * Reads the settings from {@code sdlc.console.*}.
         *
         * @param console console properties
         * @return the settings
         */
        public static Settings of(OrchestratorProperties.Console console) {
            return new Settings(console.maxSseConnections(), console.sseHeartbeatInterval(), console.sseTimeout());
        }
    }

    /**
     * Creates the hub with its own bounded write executor.
     *
     * @param engine the engine (reads the current run on connect)
     * @param auditSink the audit sink (reads the backlog on connect)
     * @param mapper run view mapper
     * @param graph workflow graph (restores committed snapshots)
     * @param codec JSON codec (restores committed snapshots)
     * @param bus the bus of committed changes
     * @param scheduler schedules the heartbeat
     * @param properties configuration ({@code sdlc.console.*})
     * @param clock clock of the heartbeat data
     */
    @Autowired
    public RunStreamHub(OrchestrationEngine engine, AuditSink auditSink, RunViewMapper mapper, WorkflowGraph graph,
                        JsonCodec codec, RunEventBus bus, TaskScheduler scheduler, OrchestratorProperties properties,
                        Clock clock) {
        this(engine, auditSink, mapper, graph, codec, bus, scheduler,
                new BoundedSendExecutor(properties.console().maxSseConnections()),
                Settings.of(properties.console()), clock);
    }

    /**
     * Creates the hub with a given write executor (tests pass a direct executor).
     *
     * @param engine the engine
     * @param auditSink the audit sink
     * @param mapper run view mapper
     * @param graph workflow graph
     * @param codec JSON codec
     * @param bus the bus of committed changes
     * @param scheduler schedules the heartbeat
     * @param sendExecutor runs the write tasks (at most one per stream at a time)
     * @param settings limits
     * @param clock clock of the heartbeat data
     */
    public RunStreamHub(OrchestrationEngine engine, AuditSink auditSink, RunViewMapper mapper, WorkflowGraph graph,
                        JsonCodec codec, RunEventBus bus, TaskScheduler scheduler, Executor sendExecutor,
                        Settings settings, Clock clock) {
        this(engine, auditSink, mapper, graph, codec, bus, scheduler, sendExecutor, settings, clock, SseEmitter::new);
    }

    /**
     * Creates the hub with a given write executor and emitter factory (tests pass emitters whose writes block).
     *
     * @param engine the engine
     * @param auditSink the audit sink
     * @param mapper run view mapper
     * @param graph workflow graph
     * @param codec JSON codec
     * @param bus the bus of committed changes
     * @param scheduler schedules the heartbeat
     * @param sendExecutor runs the write tasks (at most one per stream at a time)
     * @param settings limits
     * @param clock clock of the heartbeat data
     * @param emitters creates the emitter of a stream from its timeout in milliseconds
     */
    RunStreamHub(OrchestrationEngine engine, AuditSink auditSink, RunViewMapper mapper, WorkflowGraph graph,
                 JsonCodec codec, RunEventBus bus, TaskScheduler scheduler, Executor sendExecutor, Settings settings,
                 Clock clock, LongFunction<SseEmitter> emitters) {
        this.emitters = emitters;
        this.engine = engine;
        this.auditSink = auditSink;
        this.mapper = mapper;
        this.graph = graph;
        this.codec = codec;
        this.sendExecutor = sendExecutor;
        this.ownedExecutor = sendExecutor instanceof BoundedSendExecutor bounded ? bounded : null;
        this.settings = settings;
        this.clock = clock;
        this.subscription = bus.subscribe(this::onCommitted);
        this.heartbeat = scheduler.scheduleAtFixedRate(this::heartbeat, clock.instant().plus(
                settings.heartbeatInterval()), settings.heartbeatInterval());
    }

    // =========================================================================================== opening streams

    /**
     * Opens the event stream of a run.
     *
     * @param runId run id
     * @param lastEventId the {@code Last-Event-ID} the client resumes from (the last audit sequence number it saw), or
     *                    null for the whole trail
     * @return the emitter (its initial events are queued already)
     * @throws StreamLimitException when the maximum number of streams is open
     * @throws com.agenticsdlc.orchestrator.core.engine.RunNotFoundException for an unknown run
     */
    public SseEmitter openRun(String runId, @Nullable String lastEventId) {
        reserve();
        RunStream stream = new RunStream(runId);
        try {
            // atomic with the removal of an emptied set in RunStream.unregister()
            runStreams.compute(runId, (id, streams) -> {
                Set<RunStream> registered = streams == null ? ConcurrentHashMap.newKeySet() : streams;
                registered.add(stream);
                return registered;
            });
            Run run = engine.get(runId);
            long afterSeq = parseLastEventId(lastEventId);
            stream.initialize(run, mapper.toView(run), backlog(runId, afterSeq), afterSeq);
        } catch (RuntimeException e) {
            stream.release();
            throw e;
        }
        return stream.emitter;
    }

    /**
     * Opens the event stream of the runs list. It starts with a heartbeat, so the client knows it is connected.
     *
     * @return the emitter
     * @throws StreamLimitException when the maximum number of streams is open
     */
    public SseEmitter openList() {
        reserve();
        ListStream stream = new ListStream();
        listStreams.add(stream);
        stream.enqueue(heartbeatEvent());
        return stream.emitter;
    }

    /**
     * Returns the number of open streams.
     *
     * @return open streams
     */
    public int openStreams() {
        return connections.get();
    }

    private void reserve() {
        int open = connections.incrementAndGet();
        if (open > settings.maxConnections()) {
            connections.decrementAndGet();
            throw new StreamLimitException(settings.maxConnections());
        }
    }

    /**
     * Parses {@code Last-Event-ID}: a non-negative audit sequence number; anything else counts as absent.
     *
     * @param lastEventId the header value, or null
     * @return the sequence number to resume after (0 for the whole trail)
     */
    static long parseLastEventId(@Nullable String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(lastEventId.strip()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private List<AuditEvent> backlog(String runId, long afterSeq) {
        List<AuditEvent> events = new ArrayList<>();
        long cursor = afterSeq;
        while (events.size() < MAX_BACKLOG) {
            List<AuditEvent> page = auditSink.forRun(runId, cursor, Math.min(AUDIT_PAGE, MAX_BACKLOG - events.size()));
            events.addAll(page);
            if (page.size() < AUDIT_PAGE || page.isEmpty()) {
                break;
            }
            cursor = page.getLast().seq();
        }
        return events;
    }

    // =========================================================================================== live changes

    /**
     * Delivers a committed change to the streams: a {@code run-summary} to every list stream, and the new audit events
     * and run view to the streams of the run.
     *
     * @param committed the change
     */
    void onCommitted(RunCommitted committed) {
        if (!listStreams.isEmpty()) {
            Object summary = mapper.toSummary(committed.item());
            listStreams.forEach(stream -> stream.enqueue(event(SUMMARY_EVENT, null, summary)));
        }
        Set<RunStream> streams = runStreams.get(committed.runId());
        if (streams == null || streams.isEmpty()) {
            return;
        }
        LiveUpdate update = new LiveUpdate(committed.events(), view(committed), committed.snapshot().auditSeq(),
                committed.snapshot().updatedAt(), committed.snapshot().status().isTerminal());
        streams.forEach(stream -> stream.live(update));
    }

    /** The view of a committed snapshot, else (it cannot be restored) the engine's current view, else null. */
    private @Nullable RunView view(RunCommitted committed) {
        try {
            return mapper.toView(Run.fromSnapshot(committed.snapshot(), graph, codec));
        } catch (RuntimeException e) {
            LOG.warn("committed run snapshot could not be mapped for its event stream runId={} error={}",
                    committed.runId(), e.toString());
        }
        try {
            return mapper.toView(engine.get(committed.runId()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sends a heartbeat on every open stream. */
    void heartbeat() {
        listStreams.forEach(stream -> stream.enqueue(heartbeatEvent()));
        runStreams.values().forEach(streams -> streams.forEach(stream -> stream.enqueue(heartbeatEvent())));
    }

    private SseEmitter.SseEventBuilder heartbeatEvent() {
        return event(HEARTBEAT_EVENT, null, Map.of("time", clock.instant().toString()));
    }

    private static SseEmitter.SseEventBuilder event(String name, @Nullable String id, Object data) {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(name);
        if (id != null) {
            event.id(id);
        }
        return event.data(data, MediaType.APPLICATION_JSON);
    }

    private static SseEmitter.SseEventBuilder auditEvent(AuditEvent event) {
        return event(AUDIT_EVENT, String.valueOf(event.seq()), AuditEventView.from(event));
    }

    // =========================================================================================== shutdown

    /**
     * Ends every stream, stops the heartbeat and the writes, and unsubscribes from the bus.
     */
    @Override
    public void destroy() {
        subscription.close();
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        closeAllStreams();
        if (ownedExecutor != null) {
            ownedExecutor.close();
        }
    }

    /** Ends every open stream (shutdown, tests). */
    void closeAllStreams() {
        List.copyOf(listStreams).forEach(Stream::finish);
        runStreams.values().stream().flatMap(Set::stream).toList().forEach(Stream::finish);
    }

    // =========================================================================================== streams

    /**
     * One change of a run as the run streams consume it.
     *
     * @param events new audit events, in sequence order
     * @param view the run view after the change, or null when it could not be built
     * @param auditSeq audit sequence of the change (run version)
     * @param updatedAt update time of the change (run version within one sequence number)
     * @param terminal whether the run is terminal after the change
     */
    private record LiveUpdate(List<AuditEvent> events, @Nullable RunView view, long auditSeq, Instant updatedAt,
                              boolean terminal) {
    }

    /**
     * One queued item of a stream: an event to send (from the initial backlog or live), or the end of the stream.
     *
     * @param event the event, or null for the end
     * @param backlog whether it belongs to the initial backlog (not counted against the live limit)
     */
    private record Item(SseEmitter.@Nullable SseEventBuilder event, boolean backlog) {

        static final Item END = new Item(null, true);
    }

    /**
     * A stream with its own queue of unsent events, drained by at most one write task at a time.
     *
     * <p>The emitter is only ever completed by a thread that is not in the middle of a write: Spring's
     * {@link SseEmitter#complete()} waits for the emitter's write lock, which a write to a client that stopped reading
     * holds until the container's write timeout. A stream ended by another thread (the run event bus on overflow, the
     * heartbeat, a timeout) while its writer is in a write is therefore only marked closed; the writer completes it
     * and frees its slot as soon as that write returns, so publishing never waits for a client.</p>
     */
    private abstract class Stream {

        final SseEmitter emitter = emitters.apply(settings.timeout().toMillis());
        private final ArrayDeque<Item> queue = new ArrayDeque<>();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private boolean draining;
        /** A write task is inside {@code emitter.send}, holding the emitter's write lock. */
        private boolean writing;
        /** Backlog items still queued: the live limit applies to the other items only. */
        private int backlogQueued;
        private volatile boolean closed;

        Stream() {
            emitter.onCompletion(this::release);
            emitter.onTimeout(this::finish);
            emitter.onError(error -> release());
        }

        /** Queues a live event; a stream that is too far behind is closed (its client reconnects). */
        final void enqueue(SseEmitter.SseEventBuilder event) {
            offer(new Item(event, false));
        }

        /** Queues an event of the initial backlog (bounded by {@value #MAX_BACKLOG}, not by the live limit). */
        final void enqueueBacklog(SseEmitter.SseEventBuilder event) {
            offer(new Item(event, true));
        }

        /** Queues the end of the stream after the events queued so far. */
        final void enqueueEnd() {
            offer(Item.END);
        }

        private void offer(Item item) {
            boolean overflow = false;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (!item.backlog() && queue.size() - backlogQueued >= MAX_QUEUED_EVENTS) {
                    overflow = true;
                } else {
                    queue.add(item);
                    if (item.backlog()) {
                        backlogQueued++;
                    }
                    if (!draining) {
                        draining = true;
                        try {
                            sendExecutor.execute(this::drain);
                        } catch (RejectedExecutionException e) {
                            draining = false;
                            overflow = true;
                        }
                    }
                }
            }
            if (overflow) {
                LOG.info("closing an event stream that cannot keep up stream={}", this);
                finish();
            }
        }

        private void drain() {
            while (true) {
                Item item;
                synchronized (this) {
                    item = closed ? null : queue.poll();
                    if (item == null && !closed) {
                        draining = false;
                        return;
                    }
                    if (item != null) {
                        if (item.backlog()) {
                            backlogQueued--;
                        }
                        writing = item.event() != null;
                    }
                }
                if (item == null || item.event() == null) {
                    // the end of the stream, or ended by another thread while this writer was busy
                    endFromWriter();
                    return;
                }
                boolean failed = false;
                try {
                    emitter.send(item.event());
                } catch (IOException | RuntimeException e) {
                    LOG.debug("event stream write failed, closing stream={} error={}", this, e.toString());
                    failed = true;
                } finally {
                    synchronized (this) {
                        writing = false;
                    }
                }
                if (failed) {
                    endFromWriter();
                    return;
                }
            }
        }

        /**
         * Ends the stream now (queued events are dropped). Without a write in progress the emitter is completed and
         * the slot released right away; otherwise the writer does both when its write returns (this thread never
         * waits for a client).
         */
        final void finish() {
            synchronized (this) {
                closed = true;
                queue.clear();
                backlogQueued = 0;
                if (writing) {
                    return;
                }
            }
            completeAndRelease();
        }

        /** Ends the stream from its writer (end of stream, failed write, or a finish requested meanwhile). */
        private void endFromWriter() {
            synchronized (this) {
                closed = true;
                queue.clear();
                backlogQueued = 0;
                draining = false;
            }
            completeAndRelease();
        }

        private void completeAndRelease() {
            if (completed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (RuntimeException e) {
                    LOG.debug("event stream could not be completed: {}", e.toString());
                }
            }
            release();
        }

        /** Unregisters the stream and frees its slot, exactly once. */
        final void release() {
            if (released.compareAndSet(false, true)) {
                closed = true;
                unregister();
                connections.decrementAndGet();
            }
        }

        abstract void unregister();
    }

    /** The stream of the runs list. */
    private final class ListStream extends Stream {

        @Override
        void unregister() {
            listStreams.remove(this);
        }

        @Override
        public String toString() {
            return "runs-list";
        }
    }

    /** The stream of one run. */
    private final class RunStream extends Stream {

        private final String runId;
        private final List<LiveUpdate> buffered = new ArrayList<>();
        private boolean initialized;
        private boolean ended;
        private long lastSeq;
        private long sentAuditSeq = -1L;
        private @Nullable Instant sentUpdatedAt;

        RunStream(String runId) {
            this.runId = runId;
        }

        /** Queues the backlog and the current view, then the changes that arrived meanwhile. */
        synchronized void initialize(Run run, RunView view, List<AuditEvent> backlog, long afterSeq) {
            lastSeq = afterSeq;
            for (AuditEvent event : backlog) {
                if (event.seq() > lastSeq) {
                    enqueueBacklog(auditEvent(event));
                    lastSeq = event.seq();
                }
            }
            sendRun(view, run.auditSeq(), run.updatedAt());
            initialized = true;
            for (LiveUpdate update : buffered) {
                apply(update);
            }
            buffered.clear();
            if (run.status().isTerminal()) {
                end();
            }
        }

        /** Applies a committed change, or buffers it while the initial state is being read. */
        synchronized void live(LiveUpdate update) {
            if (ended) {
                return;
            }
            if (!initialized) {
                if (buffered.size() < MAX_QUEUED_EVENTS) {
                    buffered.add(update);
                }
                return;
            }
            apply(update);
        }

        private void apply(LiveUpdate update) {
            if (ended) {
                return;
            }
            List<AuditEvent> events = update.events();
            if (!events.isEmpty() && events.getFirst().seq() > lastSeq + 1) {
                fillGap(events.getFirst().seq());
            }
            for (AuditEvent event : events) {
                if (event.seq() > lastSeq) {
                    enqueue(auditEvent(event));
                    lastSeq = event.seq();
                }
            }
            if (update.view() != null && newer(update.auditSeq(), update.updatedAt())) {
                sendRun(update.view(), update.auditSeq(), update.updatedAt());
            }
            if (update.terminal()) {
                end();
            }
        }

        /** Sends the audit events committed by another node (or dropped by a full bus) before {@code nextSeq}. */
        private void fillGap(long nextSeq) {
            try {
                for (AuditEvent event : auditSink.forRun(runId, lastSeq, (int) Math.min(AUDIT_PAGE,
                        nextSeq - lastSeq - 1))) {
                    if (event.seq() > lastSeq && event.seq() < nextSeq) {
                        enqueue(auditEvent(event));
                        lastSeq = event.seq();
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("audit gap of an event stream could not be filled runId={} error={}", runId, e.toString());
            }
        }

        private boolean newer(long auditSeq, Instant updatedAt) {
            return auditSeq > sentAuditSeq || auditSeq == sentAuditSeq && sentUpdatedAt != null
                    && updatedAt.isAfter(sentUpdatedAt);
        }

        private void sendRun(RunView view, long auditSeq, Instant updatedAt) {
            enqueue(event(RUN_EVENT, null, view));
            sentAuditSeq = auditSeq;
            sentUpdatedAt = updatedAt;
        }

        private void end() {
            if (!ended) {
                ended = true;
                enqueueEnd();
            }
        }

        @Override
        void unregister() {
            runStreams.computeIfPresent(runId, (id, streams) -> {
                streams.remove(this);
                return streams.isEmpty() ? null : streams;
            });
        }

        @Override
        public String toString() {
            return "run:" + runId;
        }
    }
}
