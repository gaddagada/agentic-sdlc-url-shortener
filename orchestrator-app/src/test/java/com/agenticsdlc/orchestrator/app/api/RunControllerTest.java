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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.llm.ProviderCatalog;
import com.agenticsdlc.orchestrator.app.llm.ProviderUnavailableException;
import com.agenticsdlc.orchestrator.app.runs.RunConflictException;
import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.app.runs.RunListPage;
import com.agenticsdlc.orchestrator.app.runs.RunListQuery;
import com.agenticsdlc.orchestrator.app.runs.RunListSort;
import com.agenticsdlc.orchestrator.app.runs.RunListing;
import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.InvalidCommandException;
import com.agenticsdlc.orchestrator.core.engine.InvalidRunStateException;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.RunLeasedElsewhereException;
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

    @MockitoBean
    private RunListing listing;

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
    void aStoreWriteConflictIs409RunConflictWhetherRawOrWrappedByTheEngine() {
        InvalidRunStateException wrapped = new InvalidRunStateException("run run-1 could not be persisted "
                + "(org.springframework.orm.ObjectOptimisticLockingFailureException: Row was updated)");
        wrapped.initCause(new OptimisticLockingFailureException("Row was updated or deleted by another transaction"));
        when(engine.approve(eq("run-1"), eq("design"), any(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException("Row was updated or deleted by another transaction"))
                .thenThrow(wrapped);

        for (int call = 0; call < 2; call++) {
            MvcTestResult result = post("/api/v1/runs/run-1/stages/design/approve", "{}");

            assertThat(result).as("call %s", call).hasStatus(HttpStatus.CONFLICT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RUN_CONFLICT.toString());
            assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Run changed concurrently");
            assertThat(result).bodyJson().extractingPath("$.runId").isEqualTo("run-1");
            assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("run-1")
                    .contains("another engine node").doesNotContain("Row was updated");
        }
    }

    @Test
    void aRawRunConflictOrAnIllegalStateCausedByOneIs409RunConflict() {
        when(engine.stop(eq("run-1"), any(), any()))
                .thenThrow(new RunConflictException("audit batch of run run-1 does not continue the stored hash chain"))
                .thenThrow(new IllegalStateException("commit failed",
                        new OptimisticLockingFailureException("Row was updated or deleted by another transaction")));

        for (int call = 0; call < 2; call++) {
            MvcTestResult result = post("/api/v1/runs/run-1/stop", "{}");

            assertThat(result).as("call %s", call).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RUN_CONFLICT.toString());
            assertThat(result).bodyJson().extractingPath("$.runId").isEqualTo("run-1");
            assertThat(result).bodyJson().extractingPath("$.detail").asString().doesNotContain("hash chain")
                    .doesNotContain("Row was updated");
        }
    }

    @Test
    void anInvalidRunStateWithoutAStoreConflictStaysAnInvalidStateAndAPlainIllegalStateA503() {
        InvalidRunStateException unrelated = new InvalidRunStateException("stage design is not awaiting approval");
        unrelated.initCause(new IllegalArgumentException("not a store conflict"));
        when(engine.approve(eq("run-1"), eq("design"), any(), any(), any())).thenThrow(unrelated)
                .thenThrow(new IllegalStateException("engine is closed"));

        MvcTestResult state = post("/api/v1/runs/run-1/stages/design/approve", "{}");
        MvcTestResult closed = post("/api/v1/runs/run-1/stages/design/approve", "{}");

        assertThat(state).hasStatus(HttpStatus.CONFLICT);
        assertThat(state).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_STATE.toString());
        assertThat(closed).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(closed).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.SERVICE_UNAVAILABLE.toString());
    }

    @Test
    void aCommandOnARunExecutingOnAnotherNodeIs409NamingTheHolder() {
        when(engine.approve(eq("run-1"), eq("design"), any(), any(), any()))
                .thenThrow(new RunLeasedElsewhereException("run-1", "node-b-4711-3fa2c9"));

        MvcTestResult result = post("/api/v1/runs/run-1/stages/design/approve", "{}");

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.RUN_LEASED_ELSEWHERE.toString());
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Run is executing on another node");
        assertThat(result).bodyJson().extractingPath("$.holder").isEqualTo("node-b-4711-3fa2c9");
        assertThat(result).bodyJson().extractingPath("$.runId").isEqualTo("run-1");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("node-b-4711-3fa2c9")
                .contains("retry");
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
        when(listing.page(any())).thenThrow(new UnsupportedOperationException("secret internals"));

        MvcTestResult result = mvc.get().uri("/api/v1/runs").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred.");
    }

    @Test
    void listReturnsThePagedEnvelopeOfTableRowsWithoutTouchingTheEngine() {
        when(listing.page(any())).thenReturn(new RunListPage(List.of(item("run-1", "x".repeat(250))), 0, 50, 1));

        MvcTestResult result = mvc.get().uri("/api/v1/runs").accept(MediaType.APPLICATION_JSON).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.page").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.size").isEqualTo(50);
        assertThat(result).bodyJson().extractingPath("$.total").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.totalPages").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.sort").isEqualTo("updatedAt,desc");
        assertThat(result).bodyJson().extractingPath("$.items[0].id").isEqualTo("run-1");
        assertThat(result).bodyJson().extractingPath("$.items[0].progress.succeeded").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.items[0].progress.total").isEqualTo(10);
        assertThat(result).bodyJson().extractingPath("$.items[0].pendingActions").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.items[0].startedBy").isEqualTo(OPERATOR);
        assertThat(result).bodyJson().extractingPath("$.items[0].nodeId").isEqualTo("node-a");
        assertThat(result).bodyJson().extractingPath("$.items[0].autonomy").isEqualTo("GUIDED");
        assertThat(result).bodyJson().extractingPath("$.items[0].requirement").asString().hasSize(200).endsWith("…");
        ArgumentCaptor<RunListQuery> query = ArgumentCaptor.forClass(RunListQuery.class);
        verify(listing).page(query.capture());
        assertThat(query.getValue()).isEqualTo(RunListQuery.firstPage());
        verifyNoInteractions(engine);
    }

    @Test
    void listPassesPagingFiltersAndSortToTheListing() {
        when(listing.page(any())).thenReturn(new RunListPage(List.of(), 2, 25, 60));

        MvcTestResult result = mvc.get().uri("/api/v1/runs?page=2&size=25&status=running,waiting_human"
                + "&scenario=greenfield&startedBy=" + OPERATOR + "&sort=createdAt,asc").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.totalPages").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.items").asArray().isEmpty();
        ArgumentCaptor<RunListQuery> query = ArgumentCaptor.forClass(RunListQuery.class);
        verify(listing).page(query.capture());
        assertThat(query.getValue()).isEqualTo(new RunListQuery(2, 25, List.of("RUNNING", "WAITING_HUMAN"),
                "greenfield", OPERATOR, new RunListSort(RunListSort.Field.CREATED_AT, false)));
    }

    @Test
    void listCapsThePageSizeAndRejectsNegativePages() {
        assertThat(mvc.get().uri("/api/v1/runs?size=201").exchange()).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.VALIDATION_ERROR.toString());
        assertThat(mvc.get().uri("/api/v1/runs?size=0").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/runs?page=-1").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        when(listing.page(any())).thenReturn(new RunListPage(List.of(), 0, 200, 0));
        assertThat(mvc.get().uri("/api/v1/runs?size=200").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void listRejectsUnknownSortFieldsDirectionsAndStatuses() {
        MvcTestResult unknownField = mvc.get().uri("/api/v1/runs?sort=snapshotJson,desc").exchange();
        assertThat(unknownField).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unknownField).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.INVALID_ARGUMENT.toString());
        assertThat(unknownField).bodyJson().extractingPath("$.detail").asString()
                .contains("snapshotJson", "createdAt, updatedAt, endedAt, status, scenario, startedBy");
        assertThat(mvc.get().uri("/api/v1/runs?sort=updatedAt,sideways").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/runs?status=DONE").exchange()).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.detail").asString().contains("DONE");
        verifyNoInteractions(listing);
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

    private static RunListItem item(String id, String requirement) {
        return new RunListItem(id, "greenfield", "WAITING_HUMAN", null, requirement, 1, NOW, NOW.plusSeconds(5), null,
                "GUIDED", "REPO", OPERATOR, "MEDIUM", "node-a", 1, 3, 10);
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
