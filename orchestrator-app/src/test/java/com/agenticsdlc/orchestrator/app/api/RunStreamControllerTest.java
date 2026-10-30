package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.RunNotFoundException;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;

/**
 * The server-sent event streams ({@link RunStreamController}, {@link RunStreamHub}) with MockMvc async requests: the
 * initial audit backlog with sequence ids and the run view, Last-Event-ID resumption, live changes from the run event
 * bus, the final event of a terminal run, the connection cap, the heartbeat schedule and the 404 of unknown runs. The
 * hub writes on the calling thread (direct executor) and its heartbeat scheduler is a mock, so nothing is timing
 * dependent.
 */
@WebMvcTest(RunStreamController.class)
@Import({RunViewMapper.class, RunStreamControllerTest.Fixtures.class, SecuritySliceConfiguration.class})
class RunStreamControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final String REQUIREMENT = "Build a URL shortener with click analytics and expiry.";
    private static final int MAX_STREAMS = 3;
    private static final Pattern EVENT = Pattern.compile("event:(\\S+)\\n(?:id:(\\S+)\\n)?data:(.*)\\n\\n");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private RunStreamHub hub;

    @Autowired
    private RunEventBus bus;

    @Autowired
    private TaskScheduler scheduler;

    @MockitoBean
    private OrchestrationEngine engine;

    @MockitoBean
    private AuditSink auditSink;

    @AfterEach
    void closeStreams() {
        hub.closeAllStreams();
        assertThat(hub.openStreams()).isZero();
    }

    @Test
    void aRunStreamStartsWithTheAuditBacklogWithSequenceIdsThenTheRunView() {
        when(engine.get("run-1")).thenReturn(run("run-1", RunStatus.RUNNING));
        when(auditSink.forRun("run-1", 0L, RunStreamHub.AUDIT_PAGE)).thenReturn(List.of(event(1), event(2),
                event(3)));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/events").accept(MediaType.TEXT_EVENT_STREAM)
                .asyncExchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getHeader(RunStreamController.NODE_HEADER)).isNotBlank();
        List<String[]> events = events(result);
        assertThat(events).extracting(e -> e[0]).containsExactly("audit", "audit", "audit", "run");
        assertThat(events).extracting(e -> e[1]).containsExactly("1", "2", "3", null);
        assertThat(events.get(0)[2]).contains("\"seq\":1").contains("\"type\":\"RUN_CREATED\"");
        assertThat(events.get(3)[2]).contains("\"id\":\"run-1\"").contains("\"status\":\"RUNNING\"");
        assertThat(hub.openStreams()).isEqualTo(1);
    }

    @Test
    void lastEventIdResumesAfterTheLastSeenSequence() {
        when(engine.get("run-1")).thenReturn(run("run-1", RunStatus.WAITING_HUMAN));
        when(auditSink.forRun("run-1", 2L, RunStreamHub.AUDIT_PAGE)).thenReturn(List.of(event(3)));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/events").header("Last-Event-ID", "2")
                .accept(MediaType.TEXT_EVENT_STREAM).asyncExchange();

        assertThat(events(result)).extracting(e -> e[0] + "#" + e[1]).containsExactly("audit#3", "run#null");
    }

    @Test
    void committedChangesStreamTheirNewAuditEventsAndRunViewAndTheTerminalRunEndsTheStream() {
        Run run = run("run-1", RunStatus.RUNNING);
        when(engine.get("run-1")).thenReturn(run);
        when(auditSink.forRun("run-1", 0L, RunStreamHub.AUDIT_PAGE)).thenReturn(List.of(event(1)));
        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/events").accept(MediaType.TEXT_EVENT_STREAM)
                .asyncExchange();

        // a duplicate of an event already sent is skipped; the new ones follow in order, then the newer run view
        Run changed = run("run-1", RunStatus.WAITING_HUMAN);
        changed.touch(NOW.plusSeconds(10));
        bus.committed(new RunCommitted(item(changed), changed.toSnapshot(), List.of(event(1), event(2), event(3))));
        Run done = run("run-1", RunStatus.COMPLETED);
        done.touch(NOW.plusSeconds(20));
        bus.committed(new RunCommitted(item(done), done.toSnapshot(), List.of(event(4))));
        // events of other runs never reach this stream
        Run other = run("run-2", RunStatus.RUNNING);
        bus.committed(new RunCommitted(item(other), other.toSnapshot(), List.of(event("run-2", 9))));

        List<String[]> events = events(result);
        assertThat(events).extracting(e -> e[0] + "#" + e[1]).containsExactly("audit#1", "run#null", "audit#2",
                "audit#3", "run#null", "audit#4", "run#null");
        assertThat(events.get(4)[2]).contains("\"status\":\"WAITING_HUMAN\"");
        assertThat(events.get(6)[2]).as("the final event is the terminal run").contains("\"status\":\"COMPLETED\"");
        assertThat(hub.openStreams()).as("the stream ended after the terminal run").isZero();
    }

    @Test
    void aTerminalRunIsSentOnceAndTheStreamEnds() {
        when(engine.get("run-1")).thenReturn(run("run-1", RunStatus.ABORTED));
        when(auditSink.forRun("run-1", 0L, RunStreamHub.AUDIT_PAGE)).thenReturn(List.of(event(1), event(2)));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/events").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange(Duration.ofSeconds(5));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(events(result)).extracting(e -> e[0]).containsExactly("audit", "audit", "run");
        assertThat(hub.openStreams()).isZero();
    }

    @Test
    void theListStreamStartsWithAHeartbeatAndCarriesRunSummariesOfEveryCommittedChange() {
        MvcTestResult result = mvc.get().uri("/api/v1/runs/events").accept(MediaType.TEXT_EVENT_STREAM)
                .asyncExchange();

        Run run = run("run-7", RunStatus.WAITING_HUMAN);
        bus.committed(new RunCommitted(item(run), run.toSnapshot(), List.of(event("run-7", 5))));

        List<String[]> events = events(result);
        assertThat(events).extracting(e -> e[0]).containsExactly("heartbeat", "run-summary");
        assertThat(events.get(0)[2]).contains("\"time\":\"" + NOW + "\"");
        assertThat(events.get(1)[2]).contains("\"id\":\"run-7\"").contains("\"status\":\"WAITING_HUMAN\"")
                .contains("\"nodeId\":\"node-a\"").contains("\"progress\":{\"succeeded\":2,\"total\":10}");
    }

    @Test
    void theHeartbeatIsScheduledAtTheConfiguredIntervalAndReachesEveryStream() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), eq(NOW.plusSeconds(15)), eq(Duration.ofSeconds(15)));
        when(engine.get("run-1")).thenReturn(run("run-1", RunStatus.RUNNING));
        MvcTestResult runStream = mvc.get().uri("/api/v1/runs/run-1/events").accept(MediaType.TEXT_EVENT_STREAM)
                .asyncExchange();
        MvcTestResult listStream = mvc.get().uri("/api/v1/runs/events").accept(MediaType.TEXT_EVENT_STREAM)
                .asyncExchange();

        task.getValue().run();

        assertThat(events(runStream)).extracting(e -> e[0]).containsExactly("run", "heartbeat");
        assertThat(events(listStream)).extracting(e -> e[0]).containsExactly("heartbeat", "heartbeat");
    }

    @Test
    void streamsBeyondTheConnectionCapAreRefusedWith503AndRetryAfter() {
        for (int i = 0; i < MAX_STREAMS; i++) {
            assertThat(mvc.get().uri("/api/v1/runs/events").accept(MediaType.TEXT_EVENT_STREAM).asyncExchange())
                    .hasStatus(HttpStatus.OK);
        }

        MvcTestResult refused = mvc.get().uri("/api/v1/runs/events").accept(MediaType.TEXT_EVENT_STREAM).exchange();

        assertThat(refused).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "30");
        assertThat(refused).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.STREAM_LIMIT_REACHED.toString());
        assertThat(hub.openStreams()).isEqualTo(MAX_STREAMS);
        hub.closeAllStreams();
        assertThat(mvc.get().uri("/api/v1/runs/events").accept(MediaType.TEXT_EVENT_STREAM).asyncExchange())
                .as("closing streams frees their slots").hasStatus(HttpStatus.OK);
    }

    @Test
    void anUnknownRunIs404ProblemAndFreesItsSlot() {
        when(engine.get("missing")).thenThrow(new RunNotFoundException("missing"));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/missing/events").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RUN_NOT_FOUND.toString());
        assertThat(hub.openStreams()).isZero();
    }

    @Test
    void malformedLastEventIdsReplayTheWholeTrail() {
        assertThat(RunStreamHub.parseLastEventId(null)).isZero();
        assertThat(RunStreamHub.parseLastEventId(" 42 ")).isEqualTo(42L);
        assertThat(RunStreamHub.parseLastEventId("abc")).isZero();
        assertThat(RunStreamHub.parseLastEventId("-5")).isZero();
        when(engine.get("run-1")).thenReturn(run("run-1", RunStatus.RUNNING));
        when(auditSink.forRun(eq("run-1"), anyLong(), anyInt())).thenReturn(List.of());

        mvc.get().uri("/api/v1/runs/run-1/events").header("Last-Event-ID", "not-a-number")
                .accept(MediaType.TEXT_EVENT_STREAM).asyncExchange();

        verify(auditSink).forRun("run-1", 0L, RunStreamHub.AUDIT_PAGE);
    }

    /** Parses the event stream written so far: name, id (or null) and data of every complete event. */
    private static List<String[]> events(MvcTestResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        List<String[]> events = new ArrayList<>();
        Matcher matcher = EVENT.matcher(body);
        while (matcher.find()) {
            events.add(new String[] {matcher.group(1), matcher.group(2), matcher.group(3)});
        }
        return events;
    }

    private static Run run(String id, RunStatus status) {
        Run run = Run.create(id, GRAPH, "custom", REQUIREMENT, RunOptions.defaults(), NOW);
        run.setStatus(status);
        return run;
    }

    private static RunListItem item(Run run) {
        return new RunListItem(run.id(), run.scenarioKey(), run.status().name(), null, run.requirement(), 1,
                run.createdAt(), run.updatedAt(), null, "GUIDED", "NONE", "alice", "LOW", "node-a", 1, 2, 10);
    }

    private static AuditEvent event(long seq) {
        return event("run-1", seq);
    }

    private static AuditEvent event(String runId, long seq) {
        return new AuditEvent(seq, NOW.plusSeconds(seq), runId, null,
                seq == 1 ? AuditEventType.RUN_CREATED : AuditEventType.STAGE_STARTED, Actor.system(), "event " + seq,
                Map.of());
    }

    /**
     * The hub with a synchronous bus, a direct write executor, a mocked heartbeat scheduler and a fixed clock.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        WorkflowGraph workflowGraph() {
            return GRAPH;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        JsonCodec jsonCodec() {
            return JsonCodec.standard();
        }

        @Bean
        RunEventBus runEventBus() {
            return new RunEventBus(Runnable::run);
        }

        @Bean
        TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class);
        }

        @Bean
        RunStreamHub runStreamHub(OrchestrationEngine engine, AuditSink auditSink, RunViewMapper mapper,
                                  WorkflowGraph graph, JsonCodec codec, RunEventBus bus, TaskScheduler scheduler,
                                  Clock clock) {
            return new RunStreamHub(engine, auditSink, mapper, graph, codec, bus, scheduler, Runnable::run,
                    new RunStreamHub.Settings(MAX_STREAMS, Duration.ofSeconds(15), Duration.ofMinutes(5)), clock);
        }
    }
}
