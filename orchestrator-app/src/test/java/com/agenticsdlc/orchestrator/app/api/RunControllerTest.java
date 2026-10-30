package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.llm.ProviderCatalog;
import com.agenticsdlc.orchestrator.app.llm.ProviderUnavailableException;
import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.InvalidCommandException;
import com.agenticsdlc.orchestrator.core.engine.InvalidRunStateException;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.RunNotFoundException;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.RejectDisposition;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * Web slice of {@link RunController}: request validation, operator identity, Problem Details mapping of engine
 * exceptions and the mapping of requests to engine commands (engine mocked).
 */
@WebMvcTest(RunController.class)
@Import({RunViewMapper.class, RunLauncher.class, RunControllerTest.Fixtures.class, SecuritySliceConfiguration.class})
class RunControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final String REQUIREMENT = "Build a URL shortener with click analytics and expiry.";
    private static final String OPERATOR = "alice@example.com";
    private static final String APPROVAL_ID = "7f1d2c3b-0000-4000-8000-000000000001";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrchestrationEngine engine;

    @MockitoBean
    private ProviderCatalog providers;

    @Test
    void createStartsTheRunWithContractDefaultsAndReturns201() {
        when(providers.resolveAvailable(isNull())).thenReturn("deterministic");
        when(engine.start(anyString(), anyString(), any(), any())).thenReturn(run("run-1", RunOptions.defaults()));

        MvcTestResult result = mvc.post().uri("/api/v1/runs").header(OperatorIdentityResolver.HEADER, OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"requirement\": \"" + REQUIREMENT + "\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader(HttpHeaders.LOCATION, "/api/v1/runs/run-1");
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo("run-1");
        assertThat(result).bodyJson().extractingPath("$.stages.length()").isEqualTo(10);
        ArgumentCaptor<RunOptions> options = ArgumentCaptor.forClass(RunOptions.class);
        verify(engine).start(eq(REQUIREMENT), eq("custom"), options.capture(), any());
        assertThat(options.getValue().autonomy()).isEqualTo(AutonomyLevel.GUIDED);
        assertThat(options.getValue().workspace()).isEqualTo(WorkspaceMode.REPO);
        assertThat(options.getValue().providerId()).isEqualTo("deterministic");
        assertThat(options.getValue().tags()).containsEntry(RunLauncher.CREATED_BY_TAG, OPERATOR);
    }

    @Test
    void createMapsFaultsAutonomyWorkspaceAndBudget() {
        when(providers.resolveAvailable("ollama")).thenReturn("ollama");
        when(engine.start(anyString(), anyString(), any(), any())).thenReturn(run("run-2", RunOptions.defaults()));

        assertThat(post("/api/v1/runs", """
                {"requirement": "%s", "scenario": "fault-retry", "autonomy": "FULL_AUTO", "provider": "ollama",
                 "workspace": "NONE", "faults": [{"stageId": "implement", "failTimes": 2, "kind": "TRANSIENT"}],
                 "budget": {"maxAgentCalls": 25, "maxReplans": 1, "maxLlmTokens": 50000}}
                """.formatted(REQUIREMENT))).hasStatus(HttpStatus.CREATED);

        ArgumentCaptor<RunOptions> captor = ArgumentCaptor.forClass(RunOptions.class);
        verify(engine).start(eq(REQUIREMENT), eq("fault-retry"), captor.capture(), any());
        RunOptions options = captor.getValue();
        assertThat(options.autonomy()).isEqualTo(AutonomyLevel.FULL_AUTO);
        assertThat(options.workspace()).isEqualTo(WorkspaceMode.NONE);
        assertThat(options.providerId()).isEqualTo("ollama");
        assertThat(options.faults()).singleElement().satisfies(f -> {
            assertThat(f.stageId()).isEqualTo("implement");
            assertThat(f.failTimes()).isEqualTo(2);
            assertThat(f.kind()).isEqualTo(FaultKind.TRANSIENT);
        });
        assertThat(options.budgetOverride()).isNotNull();
        assertThat(options.budgetOverride().maxAgentCalls()).isEqualTo(25);
        assertThat(options.budgetOverride().maxReplans()).isEqualTo(1);
        assertThat(options.budgetOverride().maxFeedbackLoops()).isEqualTo(3);
        assertThat(options.budgetOverride().maxLlmTokens()).isEqualTo(50_000L);
    }

    @Test
    void createValidatesTheBody() {
        MvcTestResult result = post("/api/v1/runs", """
                {"requirement": "too short", "faults": [{"stageId": "", "failTimes": 0, "kind": "TRANSIENT"}],
                 "budget": {"maxAgentCalls": 0}}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.VALIDATION_ERROR.toString());
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .contains("requirement", "faults[0].stageId", "faults[0].failTimes", "budget.maxAgentCalls");
        verifyNoInteractions(engine, providers);
    }

    @Test
    void createRejectsUnknownEnumValuesAndMalformedJson() {
        assertThat(post("/api/v1/runs", "{\"requirement\": \"" + REQUIREMENT + "\", \"autonomy\": \"YOLO\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.MALFORMED_REQUEST.toString());
        assertThat(post("/api/v1/runs", "{\"requirement\": ")).hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(engine);
    }

    @Test
    void createWithoutOperatorIdIsRejected() {
        MvcTestResult result = mvc.post().uri("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\": \"" + REQUIREMENT + "\"}").exchange();

        assertOperatorIdentityProblem(result);
        verifyNoInteractions(engine);
    }

    @Test
    void createWithUnavailableProviderIs409() {
        when(providers.resolveAvailable("anthropic"))
                .thenThrow(new ProviderUnavailableException("anthropic", "ANTHROPIC_API_KEY not set"));

        MvcTestResult result = post("/api/v1/runs",
                "{\"requirement\": \"" + REQUIREMENT + "\", \"provider\": \"anthropic\"}");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.PROVIDER_UNAVAILABLE.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("ANTHROPIC_API_KEY not set");
        verifyNoInteractions(engine);
    }

    @Test
    void approveWithoutOperatorIdIs400ProblemDetail() {
        MvcTestResult result = mvc.post().uri("/api/v1/runs/run-1/stages/design/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

        assertOperatorIdentityProblem(result);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("operator identity required");
        verifyNoInteractions(engine);
    }

    @Test
    void malformedOperatorIdIs400() {
        MvcTestResult result = mvc.post().uri("/api/v1/runs/run-1/stages/design/approve")
                .header(OperatorIdentityResolver.HEADER, "alice smith").exchange();

        assertOperatorIdentityProblem(result);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("invalid");
        verifyNoInteractions(engine);
    }

    @Test
    void approvePassesTheHumanOperatorAndComment() {
        when(engine.approve(eq("run-1"), eq("design"), any(), any(), any()))
                .thenReturn(run("run-1", RunOptions.defaults()));

        assertThat(post("/api/v1/runs/run-1/stages/design/approve", "{\"comment\": \"looks good\"}"))
                .hasStatus(HttpStatus.OK);

        verify(engine).approve("run-1", "design", Actor.human(OPERATOR), "looks good", null);
    }

    @Test
    void approvePassesTheReviewedApprovalId() {
        when(engine.approve(eq("run-1"), eq("design"), any(), any(), any()))
                .thenReturn(run("run-1", RunOptions.defaults()));

        assertThat(post("/api/v1/runs/run-1/stages/design/approve",
                "{\"comment\": \"ok\", \"approvalId\": \"" + APPROVAL_ID + "\"}")).hasStatus(HttpStatus.OK);

        verify(engine).approve("run-1", "design", Actor.human(OPERATOR), "ok", APPROVAL_ID);
    }

    @Test
    void approveWithAStaleApprovalIdIs409WithTheEngineMessage() {
        when(engine.approve(eq("run-1"), eq("design"), any(), any(), eq("stale-id")))
                .thenThrow(new InvalidRunStateException("approval stale-id is no longer pending; the stage now awaits "
                        + "approval " + APPROVAL_ID));

        MvcTestResult result = post("/api/v1/runs/run-1/stages/design/approve", "{\"approvalId\": \"stale-id\"}");

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_STATE.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .startsWith("approval stale-id is no longer pending");
    }

    @Test
    void approveRejectsAMalformedApprovalId() {
        MvcTestResult result = post("/api/v1/runs/run-1/stages/design/approve", "{\"approvalId\": \"a b<c>\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("approvalId");
        verifyNoInteractions(engine);
    }

    @Test
    void approveWithoutBodyIsAllowed() {
        when(engine.approve(eq("run-1"), eq("design"), any(), isNull(), isNull())).thenReturn(run("run-1",
                RunOptions.defaults()));

        assertThat(mvc.post().uri("/api/v1/runs/run-1/stages/design/approve")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void reservedOperatorIdsAreRejected() {
        for (String reserved : new String[] {"engine", "System", "deterministic", "fallback:deterministic"}) {
            MvcTestResult result = mvc.post().uri("/api/v1/runs/run-1/stages/design/approve")
                    .header(OperatorIdentityResolver.HEADER, reserved).exchange();

            assertOperatorIdentityProblem(result);
        }
        verifyNoInteractions(engine);
    }

    @Test
    void rejectRequiresReasonAndDisposition() {
        MvcTestResult result = post("/api/v1/runs/run-1/stages/design/reject", "{\"reason\": \" \"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .contains("reason", "disposition");
        verifyNoInteractions(engine);
    }

    @Test
    void rejectPassesReasonDispositionAndApprovalId() {
        when(engine.reject(any(), any(), any(), any(), any(), any())).thenReturn(run("run-1", RunOptions.defaults()));

        assertThat(post("/api/v1/runs/run-1/stages/design/reject",
                "{\"reason\": \"needs caching\", \"disposition\": \"REWORK\"}")).hasStatus(HttpStatus.OK);
        assertThat(post("/api/v1/runs/run-1/stages/design/reject", "{\"reason\": \"no\", \"disposition\": "
                + "\"ABORT\", \"approvalId\": \"" + APPROVAL_ID + "\"}")).hasStatus(HttpStatus.OK);

        verify(engine).reject("run-1", "design", Actor.human(OPERATOR), "needs caching", RejectDisposition.REWORK,
                null);
        verify(engine).reject("run-1", "design", Actor.human(OPERATOR), "no", RejectDisposition.ABORT, APPROVAL_ID);
    }

    @Test
    void clarifyPassesTheAnswers() {
        when(engine.clarify(any(), any(), any())).thenReturn(run("run-1", RunOptions.defaults()));

        assertThat(post("/api/v1/runs/run-1/clarify", "{\"answers\": {\"q1\": \"redirects\", \"q2\": \"99.9%\"}}"))
                .hasStatus(HttpStatus.OK);

        verify(engine).clarify("run-1", Actor.human(OPERATOR), Map.of("q1", "redirects", "q2", "99.9%"));
    }

    @Test
    void unknownRunIs404() {
        when(engine.get("missing")).thenThrow(new RunNotFoundException("missing"));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/missing").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RUN_NOT_FOUND.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Run not found: missing");
    }

    @Test
    void commandInTheWrongStateIs409WithTheEngineMessage() {
        when(engine.resume(eq("run-1"), any()))
                .thenThrow(new InvalidRunStateException("a policy denial cannot be resumed; re-plan with a changed "
                        + "requirement"));

        MvcTestResult result = post("/api/v1/runs/run-1/resume", "{}");

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_STATE.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .startsWith("a policy denial cannot be resumed");
    }

    @Test
    void invalidCommandIs409() {
        when(engine.replan(eq("run-1"), any(), anyString()))
                .thenThrow(new InvalidCommandException("re-plan budget exhausted (3)"));

        MvcTestResult result = post("/api/v1/runs/run-1/replan", "{\"requirement\": \"" + REQUIREMENT + "\"}");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_COMMAND.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("re-plan budget exhausted (3)");
    }

    @Test
    void aClosedEngineIs503WithoutTheEngineMessage() {
        when(engine.stop(eq("run-1"), any(), any())).thenThrow(new IllegalStateException("orchestration engine is "
                + "closed"));

        MvcTestResult result = post("/api/v1/runs/run-1/stop", "{}");

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.SERVICE_UNAVAILABLE.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("control plane is shutting down");
    }

    @Test
    void illegalArgumentsAre400WithAGenericDetail() {
        when(engine.replan(eq("run-1"), any(), anyString()))
                .thenThrow(new IllegalArgumentException("internal detail from a library"));

        MvcTestResult result = post("/api/v1/runs/run-1/replan", "{\"requirement\": \"" + REQUIREMENT + "\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_ARGUMENT.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("The request contains an invalid argument.");
    }

    @Test
    void unexpectedErrorIs500WithoutInternals() {
        when(engine.list()).thenThrow(new UnsupportedOperationException("secret internals"));

        MvcTestResult result = mvc.get().uri("/api/v1/runs").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred.");
    }

    @Test
    void listReturnsSummaries() {
        when(engine.list()).thenReturn(List.of(run("run-1", RunOptions.defaults())));

        MvcTestResult result = mvc.get().uri("/api/v1/runs").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[0].id").isEqualTo("run-1");
        assertThat(result).bodyJson().extractingPath("$[0].progress.total").isEqualTo(10);
        assertThat(result).bodyJson().extractingPath("$[0].pendingActions").isEqualTo(0);
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).header(OperatorIdentityResolver.HEADER, OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertOperatorIdentityProblem(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.OPERATOR_IDENTITY_REQUIRED.toString());
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Operator identity required");
    }

    private static Run run(String id, RunOptions options) {
        return Run.create(id, GRAPH, "custom", REQUIREMENT, options, NOW);
    }

    /**
     * Beans the mapper needs.
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
        FourEyesGuard fourEyesGuard() {
            return FourEyesGuard.of(false);
        }
    }
}
