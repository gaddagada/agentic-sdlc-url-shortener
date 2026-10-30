package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.app.api.ProblemTypes;
import com.agenticsdlc.orchestrator.app.scenario.ScenarioCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the real control plane (engine, deterministic agents, JPA persistence on in-memory H2, immediate retries)
 * through the REST API, the way the operator console does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrchestratorEndToEndTest {

    private static final String OPERATOR = "alice";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void greenfieldCompletesThroughDesignAndReleaseApprovals() {
        String id = startScenario("greenfield");

        JsonNode atDesign = awaitRun(id, r -> hasPendingApproval(r, "design"));
        assertThat(atDesign.path("status").asString()).isEqualTo("WAITING_HUMAN");
        JsonNode designAction = atDesign.path("pendingActions").get(0);
        assertThat(designAction.path("type").asString()).isEqualTo("APPROVAL");
        assertThat(designAction.path("gateKind").asString()).isEqualTo("EXIT");
        assertThat(designAction.path("reason").asString()).contains("GOV-001");
        assertThat(stage(atDesign, "decompose").path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(stage(atDesign, "impact").path("status").asString()).isEqualTo("SUCCEEDED");

        assertThat(command(id, "/stages/design/approve", "{\"comment\": \"design approved\"}"))
                .hasStatus(HttpStatus.OK);
        awaitRun(id, r -> hasPendingApproval(r, "release-readiness"));
        assertThat(command(id, "/stages/release-readiness/approve", "{\"comment\": \"ship it\"}"))
                .hasStatus(HttpStatus.OK);

        JsonNode done = awaitRun(id, r -> "COMPLETED".equals(r.path("status").asString()));
        assertThat(done.path("pendingActions")).isEmpty();
        assertThat(done.path("endedAt").isNull()).isFalse();
        assertThat(done.path("timing").path("endToEndMs").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(done.path("counters").path("approvalsGranted").asInt()).isEqualTo(2);
        assertThat(done.path("createdBy").asString()).isEqualTo(OPERATOR);
        assertThat(stage(done, "design").path("approval").path("decidedBy").asString()).isEqualTo(OPERATOR);

        JsonNode audit = get("/api/v1/runs/" + id + "/audit?limit=5000");
        List<String> types = texts(audit, "type");
        assertThat(types).contains("RUN_CREATED", "APPROVAL_REQUESTED", "APPROVAL_GRANTED", "RUN_COMPLETED");
        assertThat(audit).anySatisfy(e -> {
            assertThat(e.path("type").asString()).isEqualTo("APPROVAL_GRANTED");
            assertThat(e.path("actor").path("type").asString()).isEqualTo("HUMAN");
            assertThat(e.path("actor").path("id").asString()).isEqualTo(OPERATOR);
        });
        long lastSeq = audit.get(audit.size() - 1).path("seq").asLong();
        assertThat(get("/api/v1/runs/" + id + "/audit?afterSeq=" + lastSeq)).isEmpty();
        JsonNode integrity = get("/api/v1/runs/" + id + "/audit/integrity");
        assertThat(integrity.path("valid").asBoolean()).as(integrity.path("summary").asString()).isTrue();
        assertThat(integrity.path("length").asLong()).isEqualTo(audit.size()).isEqualTo(lastSeq);
        assertThat(absent(integrity.path("brokenAtSeq"))).isTrue();
        assertThat(mvc.get().uri("/api/v1/runs/no-such-run/audit/integrity").exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        JsonNode release = get("/api/v1/runs/" + id + "/artifacts/release-readiness");
        assertThat(absent(release.path("signature"))).as("no signing key in the test profile").isTrue();
        assertThat(absent(release.path("signatureVerified"))).isTrue();

        JsonNode artifacts = get("/api/v1/runs/" + id + "/artifacts");
        long accepted = 0;
        for (JsonNode artifact : artifacts) {
            accepted += artifact.path("accepted").asBoolean() ? 1 : 0;
        }
        assertThat(accepted).isEqualTo(10);

        JsonNode design = get("/api/v1/runs/" + id + "/artifacts/design");
        assertThat(design.path("kind").asString()).isEqualTo("DESIGN_SPEC");
        assertThat(design.path("payload").isObject()).isTrue();
        assertThat(design.path("payload").path("components").isArray()).isTrue();
        assertThat(design.path("markdown").asString()).isNotBlank();
        assertThat(design.path("provenance").path("derivedFrom").path("decompose").asInt()).isEqualTo(1);

        JsonNode lineage = get("/api/v1/runs/" + id + "/lineage");
        // the engine finalizes the release change record with the approvals as a new artifact version
        assertThat(lineage.path("nodes").size()).isEqualTo(artifacts.size()).isGreaterThanOrEqualTo(10);
        assertThat(lineage.path("edges").size()).isPositive();

        JsonNode graph = get("/api/v1/runs/" + id + "/graph");
        assertThat(graph.path("nodes").size()).isEqualTo(10);
        assertThat(texts(graph.path("nodes"), "status")).containsOnly("SUCCEEDED");
        assertThat(graph.path("layers").get(0).get(0).asString()).isEqualTo("intake");

        JsonNode metrics = get("/api/v1/metrics/reliability");
        assertThat(metrics.path("runs").path("completed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.path("approvals").path("approved").asLong()).isGreaterThanOrEqualTo(2L);

        JsonNode runs = get("/api/v1/runs");
        assertThat(runs).anySatisfy(r -> {
            assertThat(r.path("id").asString()).isEqualTo(id);
            assertThat(r.path("progress").path("succeeded").asInt()).isEqualTo(10);
            assertThat(r.path("pendingActions").asInt()).isZero();
        });
    }

    @Test
    void ambiguousRequirementPausesForClarificationAndContinuesAfterTheAnswers() {
        String id = startScenario("ambiguous");

        JsonNode waiting = awaitRun(id, r -> r.path("pendingActions").size() > 0
                && "CLARIFICATION".equals(r.path("pendingActions").get(0).path("type").asString()));
        assertThat(waiting.path("status").asString()).isEqualTo("WAITING_HUMAN");
        assertThat(stage(waiting, "intake").path("status").asString()).isEqualTo("AWAITING_CLARIFICATION");
        JsonNode questions = waiting.path("pendingActions").get(0).path("questions");
        assertThat(questions.size()).isGreaterThanOrEqualTo(1);

        Map<String, String> answers = new LinkedHashMap<>();
        for (JsonNode q : questions) {
            answers.put(q.path("id").asString(), q.path("suggestedAnswers").get(0).asString());
        }
        assertThat(command(id, "/clarify", json.writeValueAsString(Map.of("answers", answers))))
                .hasStatus(HttpStatus.OK);

        JsonNode next = awaitRun(id, r -> hasPendingApproval(r, "design"));
        assertThat(stage(next, "intake").path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(next.path("clarification").path("answers").size()).isEqualTo(answers.size());
        assertThat(next.path("clarification").path("answeredBy").asString()).isEqualTo(OPERATOR);
        JsonNode spec = get("/api/v1/runs/" + id + "/artifacts/intake");
        assertThat(spec.path("payload").path("normalizedStatement").asString()).contains("Clarifications:");
        assertThat(spec.path("payload").path("classification").asString()).isNotEqualTo("AMBIGUOUS");

        MvcTestResult again = command(id, "/clarify", json.writeValueAsString(Map.of("answers", answers)));
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void policyDenialIsSafeStoppedAndCannotBeResumed() {
        MvcTestResult started = mvc.post().uri("/api/v1/scenarios/policy-denial/run")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange();
        assertThat(started).hasStatus(HttpStatus.CREATED);
        JsonNode run = body(started);
        String id = run.path("id").asString();
        assertThat(run.path("status").asString()).isEqualTo("SAFE_STOPPED");
        assertThat(run.path("haltReason").asString()).isEqualTo("POLICY_DENIED");
        assertThat(texts(run.path("policyFindings"), "verdict")).contains("DENY");

        MvcTestResult resume = command(id, "/resume", "{}");
        assertThat(resume).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(resume).bodyJson().extractingPath("$.detail").asString().contains("policy denial");

        assertThat(texts(get("/api/v1/runs/" + id + "/audit"), "type")).contains("POLICY_DENIED", "SAFE_STOP");
        assertThat(get("/api/v1/runs/" + id + "/artifacts")).isEmpty();
    }

    @Test
    void faultRetryScenarioRecoversWithTwoRetriesAndCompletes() {
        String id = startScenario("fault-retry");

        JsonNode atRelease = awaitRun(id, r -> hasPendingApproval(r, "release-readiness"));
        assertThat(atRelease.path("pendingActions").size()).isEqualTo(1);
        assertThat(command(id, "/stages/release-readiness/approve", "{}")).hasStatus(HttpStatus.OK);

        JsonNode done = awaitRun(id, r -> "COMPLETED".equals(r.path("status").asString()));
        assertThat(done.path("counters").path("retries").asInt()).isEqualTo(2);
        assertThat(done.path("counters").path("fallbacks").asInt()).isZero();
        assertThat(stage(done, "implement").path("attempts").asInt()).isEqualTo(3);
        assertThat(done.path("timing").path("firstFailureAt").isNull()).isFalse();
        assertThat(done.path("timing").path("recoveredAt").isNull()).isFalse();
        assertThat(texts(get("/api/v1/runs/" + id + "/audit?limit=5000"), "type")).contains("RETRY_SCHEDULED");
    }

    @Test
    void theVersionUnderReviewIsShownButNotAcceptedUntilApproved() {
        String id = startScenario("greenfield");

        JsonNode atDesign = awaitRun(id, r -> hasPendingApproval(r, "design"));
        JsonNode action = pendingApproval(atDesign, "design");
        int reviewVersion = action.path("reviewVersion").asInt();
        assertThat(reviewVersion).isEqualTo(1);
        assertThat(action.path("reviewHash").asString()).hasSize(64);
        JsonNode design = stage(atDesign, "design");
        assertThat(design.path("artifactVersion").asInt()).isEqualTo(reviewVersion);
        assertThat(design.path("acceptedVersion").isIntegralNumber()).as("nothing accepted yet").isFalse();
        assertThat(design.path("approval").path("artifactVersion").asInt()).isEqualTo(reviewVersion);
        assertThat(design.path("approval").path("artifactHash").asString())
                .isEqualTo(action.path("reviewHash").asString());
        JsonNode graphNode = node(get("/api/v1/runs/" + id + "/graph"), "design");
        assertThat(graphNode.path("artifactVersion").asInt()).isEqualTo(reviewVersion);
        assertThat(graphNode.path("acceptedVersion").isIntegralNumber()).isFalse();
        JsonNode summary = artifactSummary(get("/api/v1/runs/" + id + "/artifacts"), "design", reviewVersion);
        assertThat(summary.path("accepted").asBoolean()).isFalse();
        assertThat(summary.path("hash").asString()).isEqualTo(action.path("reviewHash").asString());
        assertThat(mvc.get().uri("/api/v1/runs/" + id + "/artifacts/design").exchange())
                .as("no accepted version yet").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/runs/" + id + "/artifacts/design?version=" + reviewVersion).path("accepted")
                .asBoolean()).isFalse();

        assertThat(command(id, "/stages/design/approve",
                "{\"approvalId\": \"" + action.path("approvalId").asString() + "\"}")).hasStatus(HttpStatus.OK);

        JsonNode approved = get("/api/v1/runs/" + id);
        assertThat(stage(approved, "design").path("acceptedVersion").asInt()).isEqualTo(reviewVersion);
        assertThat(artifactSummary(get("/api/v1/runs/" + id + "/artifacts"), "design", reviewVersion)
                .path("accepted").asBoolean()).isTrue();
        assertThat(get("/api/v1/runs/" + id + "/artifacts/design").path("version").asInt()).isEqualTo(reviewVersion);
    }

    @Test
    void aStaleApprovalIdAfterAReplanIs409AndTheFreshIdSucceeds() {
        String id = startScenario("greenfield");
        JsonNode atDesign = awaitRun(id, r -> hasPendingApproval(r, "design"));
        String staleId = pendingApproval(atDesign, "design").path("approvalId").asString();

        assertThat(command(id, "/replan",
                json.writeValueAsString(Map.of("requirement", ScenarioCatalog.REPLAN_REQUIREMENT))))
                .hasStatus(HttpStatus.OK);
        JsonNode replanned = awaitRun(id, r -> hasPendingApproval(r, "design")
                && !staleId.equals(pendingApproval(r, "design").path("approvalId").asString()));
        JsonNode fresh = pendingApproval(replanned, "design");
        String freshId = fresh.path("approvalId").asString();

        MvcTestResult stale = command(id, "/stages/design/approve", "{\"approvalId\": \"" + staleId + "\"}");
        assertThat(stale).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(stale).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_STATE.toString());
        assertThat(stale).bodyJson().extractingPath("$.detail").asString().contains(staleId)
                .contains("no longer pending");
        MvcTestResult staleReject = command(id, "/stages/design/reject",
                "{\"reason\": \"old\", \"disposition\": \"REWORK\", \"approvalId\": \"" + staleId + "\"}");
        assertThat(staleReject).hasStatus(HttpStatus.CONFLICT);
        assertThat(pendingApproval(get("/api/v1/runs/" + id), "design").path("approvalId").asString())
                .as("the refused commands changed nothing").isEqualTo(freshId);

        assertThat(command(id, "/stages/design/approve", "{\"approvalId\": \"" + freshId + "\"}"))
                .hasStatus(HttpStatus.OK);
        JsonNode approved = get("/api/v1/runs/" + id);
        assertThat(stage(approved, "design").path("approval").path("id").asString()).isEqualTo(freshId);
        assertThat(stage(approved, "design").path("approval").path("status").asString()).isEqualTo("APPROVED");
        assertThat(stage(approved, "design").path("acceptedVersion").asInt())
                .isEqualTo(fresh.path("reviewVersion").asInt());
    }

    @Test
    void scenarioRunsAcceptBudgetOverridesInAnOptionalBody() {
        MvcTestResult started = mvc.post().uri("/api/v1/scenarios/greenfield/run?provider=deterministic")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"deterministic\", \"budget\": {\"maxLlmTokens\": 12345, "
                        + "\"maxAgentCalls\": 77}}").exchange();

        assertThat(started).hasStatus(HttpStatus.CREATED);
        JsonNode run = body(started);
        assertThat(run.path("provider").asString()).isEqualTo("deterministic");
        assertThat(run.path("budget").path("maxLlmTokens").asLong()).isEqualTo(12345L);
        assertThat(run.path("budget").path("maxAgentCalls").asInt()).isEqualTo(77);

        MvcTestResult conflicting = mvc.post().uri("/api/v1/scenarios/greenfield/run?provider=deterministic")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"ollama\"}").exchange();
        assertThat(conflicting).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(conflicting).bodyJson().extractingPath("$.detail").asString().contains("provider given twice");

        MvcTestResult invalid = mvc.post().uri("/api/v1/scenarios/greenfield/run")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\": {\"maxAgentCalls\": 0, \"maxLlmTokens\": -1}}").exchange();
        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalid).bodyJson().extractingPath("$.errors[*].field").asArray()
                .contains("budget.maxAgentCalls", "budget.maxLlmTokens");
    }

    @Test
    void auditPagesAreBoundedByTheLimit() {
        String id = startScenario("greenfield");
        awaitRun(id, r -> hasPendingApproval(r, "design"));

        JsonNode firstPage = get("/api/v1/runs/" + id + "/audit?limit=3");
        assertThat(firstPage.size()).isEqualTo(3);
        long lastSeq = firstPage.get(2).path("seq").asLong();
        JsonNode next = get("/api/v1/runs/" + id + "/audit?afterSeq=" + lastSeq + "&limit=2");
        assertThat(next.size()).isEqualTo(2);
        assertThat(next.get(0).path("seq").asLong()).isGreaterThan(lastSeq);
        assertThat(mvc.get().uri("/api/v1/runs/" + id + "/audit?limit=0").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownScenarioIs404AndUnavailableProviderIs409() {
        assertThat(mvc.post().uri("/api/v1/scenarios/nope/run").header(OperatorIdentityResolver.HEADER, OPERATOR)
                .exchange()).hasStatus(HttpStatus.NOT_FOUND);
        MvcTestResult anthropic = mvc.post().uri("/api/v1/scenarios/greenfield/run?provider=anthropic")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange();
        assertThat(anthropic).hasStatus(HttpStatus.CONFLICT);
        assertThat(anthropic).bodyJson().extractingPath("$.detail").asString().contains("ANTHROPIC_API_KEY not set");
        assertThat(mvc.post().uri("/api/v1/scenarios/greenfield/run?provider=ollama")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange()).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void catalogEndpointsDescribeTheControlPlane() {
        assertThat(texts(get("/api/v1/scenarios"), "key")).containsExactly("greenfield", "brownfield", "ambiguous",
                "policy-denial", "fault-retry", "fault-fallback", "replan");
        assertThat(texts(get("/api/v1/providers"), "id")).containsExactly("deterministic", "anthropic", "ollama");
        JsonNode workflow = get("/api/v1/workflows/default");
        assertThat(workflow.path("id").asString()).isEqualTo("sdlc-default");
        assertThat(workflow.path("stages").size()).isEqualTo(10);
        JsonNode index = get("/api/v1/workspace/index");
        assertThat(texts(index.path("types"), "fqcn"))
                .contains("com.agenticsdlc.shortener.api.LinkController");
        assertThat(mvc.get().uri("/").exchange()).hasStatus(HttpStatus.OK);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private String startScenario(String key) {
        MvcTestResult result = mvc.post().uri("/api/v1/scenarios/" + key + "/run")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return body(result).path("id").asString();
    }

    private MvcTestResult command(String id, String path, String body) {
        return mvc.post().uri("/api/v1/runs/" + id + path).header(OperatorIdentityResolver.HEADER, OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private JsonNode awaitRun(String id, Predicate<JsonNode> condition) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> {
            JsonNode run = get("/api/v1/runs/" + id);
            last.set(run);
            String status = run.path("status").asString();
            assertThat(status).as("run %s unexpectedly ended: %s", id, run.path("haltDetail").asString())
                    .isNotIn("ABORTED");
            return condition.test(run);
        });
        return last.get();
    }

    private JsonNode get(String uri) {
        MvcTestResult result = mvc.get().uri(uri).exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return body(result);
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private static boolean hasPendingApproval(JsonNode run, String stageId) {
        for (JsonNode action : run.path("pendingActions")) {
            if ("APPROVAL".equals(action.path("type").asString())
                    && stageId.equals(action.path("stageId").asString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode pendingApproval(JsonNode run, String stageId) {
        for (JsonNode action : run.path("pendingActions")) {
            if ("APPROVAL".equals(action.path("type").asString())
                    && stageId.equals(action.path("stageId").asString())) {
                return action;
            }
        }
        throw new AssertionError("no pending approval for " + stageId);
    }

    private static JsonNode node(JsonNode graph, String stageId) {
        for (JsonNode node : graph.path("nodes")) {
            if (stageId.equals(node.path("id").asString())) {
                return node;
            }
        }
        throw new AssertionError("no graph node " + stageId);
    }

    private static JsonNode artifactSummary(JsonNode artifacts, String stageId, int version) {
        for (JsonNode artifact : artifacts) {
            if (stageId.equals(artifact.path("stageId").asString()) && artifact.path("version").asInt() == version) {
                return artifact;
            }
        }
        throw new AssertionError("no artifact " + stageId + " v" + version);
    }

    private static JsonNode stage(JsonNode run, String stageId) {
        for (JsonNode stage : run.path("stages")) {
            if (stageId.equals(stage.path("id").asString())) {
                return stage;
            }
        }
        throw new AssertionError("no stage " + stageId);
    }

    private static boolean absent(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    private static List<String> texts(JsonNode array, String field) {

        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asString()));
        return values;
    }
}
