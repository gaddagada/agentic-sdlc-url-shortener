package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

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
 * live events pile up beyond the limit is closed (its client reconnects with Last-Event-ID) and frees its slot.
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

    private void drainWrites() {
        while (!pendingWrites.isEmpty()) {
            pendingWrites.poll().run();
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
