package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;

/**
 * {@link RunStreamHub} with a write executor that runs its tasks only when the test says so, the way a slow client
 * makes writes lag: a backlog longer than the live event limit is still delivered completely, while a stream whose
 * live events pile up beyond the limit is closed (its client reconnects with Last-Event-ID) and frees its slot. A
 * stream whose write is blocked (a client that stopped reading) is closed without the publishing thread ever waiting
 * for that write.
 */
class RunStreamHubTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final OrchestrationEngine engine = mock(OrchestrationEngine.class);
    private final AuditSink auditSink = mock(AuditSink.class);
    private final RunEventBus bus = new RunEventBus(Runnable::run);
    private final Queue<Runnable> pendingWrites = new ArrayDeque<>();
    private final Executor deferred = pendingWrites::add;
    private final RunStreamHub hub = new RunStreamHub(engine, auditSink, new RunViewMapper(GRAPH, CLOCK), GRAPH,
            JsonCodec.standard(), bus, mock(TaskScheduler.class), deferred,
            new RunStreamHub.Settings(5, Duration.ofSeconds(15), Duration.ofMinutes(5)), CLOCK);

    @Test
    void aBacklogLongerThanTheLiveLimitIsDeliveredCompletely() {
        Run run = Run.create("long", GRAPH, "custom", "Build a URL shortener.", RunOptions.defaults(), NOW);
        when(engine.get("long")).thenReturn(run);
        int backlog = RunStreamHub.MAX_QUEUED_EVENTS + 500;
        List<AuditEvent> page1 = events(1, RunStreamHub.AUDIT_PAGE);
        List<AuditEvent> page2 = events(RunStreamHub.AUDIT_PAGE + 1, backlog);
        when(auditSink.forRun(eq("long"), eq(0L), anyInt())).thenReturn(page1);
        when(auditSink.forRun(eq("long"), eq((long) RunStreamHub.AUDIT_PAGE), anyInt())).thenReturn(page2);

        hub.openRun("long", null);

        assertThat(hub.openStreams()).as("not closed for its backlog").isEqualTo(1);
        drainWrites();
        assertThat(hub.openStreams()).isEqualTo(1);
    }

    @Test
    void aStreamWhoseLiveEventsPileUpIsClosedAndFreesItsSlot() {
        Run run = Run.create("busy", GRAPH, "custom", "Build a URL shortener.", RunOptions.defaults(), NOW);
        when(engine.get("busy")).thenReturn(run);
        when(auditSink.forRun(eq("busy"), anyLong(), anyInt())).thenReturn(List.of());
        hub.openRun("busy", null);

        for (int seq = 1; seq <= RunStreamHub.MAX_QUEUED_EVENTS + 1; seq++) {
            bus.committed(new RunCommitted(item(run), run.toSnapshot(), List.of(event(seq))));
        }

        assertThat(hub.openStreams()).isZero();
    }

    @Test
    void closingAStreamWhoseWriteIsBlockedNeverBlocksThePublisher() throws Exception {
        BlockingEmitter emitter = new BlockingEmitter();
        RunStreamHub blocking = new RunStreamHub(engine, auditSink, new RunViewMapper(GRAPH, CLOCK), GRAPH,
                JsonCodec.standard(), bus, mock(TaskScheduler.class), new BoundedSendExecutor(5),
                new RunStreamHub.Settings(5, Duration.ofSeconds(15), Duration.ofMinutes(5)), CLOCK, timeout -> emitter);
        Run run = Run.create("busy", GRAPH, "custom", "Build a URL shortener.", RunOptions.defaults(), NOW);
        try {
            blocking.openList(); // its first event starts a write that never returns: the client stopped reading
            assertThat(emitter.writing.await(10, TimeUnit.SECONDS)).isTrue();

            // the bus delivers on this thread (as its dispatcher thread would): the overflow closes the stream
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                for (int seq = 1; seq <= RunStreamHub.MAX_QUEUED_EVENTS + 1; seq++) {
                    bus.committed(new RunCommitted(item(run), run.toSnapshot(), List.of(event(seq))));
                }
            }, "the publisher waited for the blocked write");
            assertThat(emitter.completed).as("completed by the writer once its write returns").isFalse();
        } finally {
            emitter.release.countDown();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> emitter.completed && blocking.openStreams() == 0);
        blocking.destroy();
    }

    private void drainWrites() {
        while (!pendingWrites.isEmpty()) {
            pendingWrites.poll().run();
        }
    }

    /**
     * An emitter whose writes block until released, holding the emitter's write lock like Spring's own send does
     * while it writes to a client that does not read (so {@link SseEmitter#complete()} waits for it).
     */
    private static final class BlockingEmitter extends SseEmitter {

        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            writeLock.lock();
            try {
                writing.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void complete() {
            super.complete();
            completed = true;
        }
    }

    private static List<AuditEvent> events(int from, int to) {
        List<AuditEvent> events = new ArrayList<>();
        for (int seq = from; seq <= to; seq++) {
            events.add(event(seq));
        }
        return events;
    }

    private static AuditEvent event(long seq) {
        return new AuditEvent(seq, NOW, "run", null, AuditEventType.STAGE_STARTED, Actor.system(), "event " + seq,
                Map.of());
    }

    private static RunListItem item(Run run) {
        return new RunListItem(run.id(), run.scenarioKey(), run.status().name(), null, run.requirement(), 1,
                run.createdAt(), run.updatedAt(), null, null, null, null, null, null, null, null, null);
    }
}
