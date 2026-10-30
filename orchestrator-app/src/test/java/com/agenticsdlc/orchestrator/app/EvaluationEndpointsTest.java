package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import com.agenticsdlc.orchestrator.app.persistence.AuditEventRepository;
import com.agenticsdlc.orchestrator.app.persistence.RunRepository;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.RunNotFoundException;
import com.agenticsdlc.orchestrator.core.model.RunStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evaluation endpoints on the real control plane: a run's evaluation as JSON and Markdown, and the bundled suite
 * run on isolated in-memory engines that leave the production store, audit trail and engine untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvaluationEndpointsTest {

    private static final String OPERATOR = "evaluator";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RunRepository runs;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private OrchestrationEngine engine;

    @Test
    void aRunIsEvaluatedAsJsonAndAsMarkdown() {
        MvcTestResult started = mvc.post().uri("/api/v1/scenarios/greenfield/run")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange();
        assertThat(started).hasStatus(HttpStatus.CREATED);
        String id = body(started).path("id").asString();
        await().atMost(Duration.ofSeconds(30)).until(() -> engine.get(id).status() == RunStatus.WAITING_HUMAN);

        MvcTestResult report = mvc.get().uri("/api/v1/runs/" + id + "/evaluation").exchange();
        assertThat(report).hasStatus(HttpStatus.OK).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        JsonNode view = body(report);
        assertThat(view.path("runId").asString()).isEqualTo(id);
        assertThat(view.path("overall").asDouble()).isBetween(0.0, 1.0);
        assertThat(view.path("stages").get(0).path("stageId").asString()).isEqualTo("intake");
        assertThat(view.path("stages").get(0).path("criteria").get(0).has("rationale")).isTrue();
        assertThat(view.path("notes").toString()).contains("design");

        MvcTestResult markdown = mvc.get().uri("/api/v1/runs/" + id + "/evaluation?format=markdown")
                .accept(MediaType.parseMediaType("text/markdown")).exchange();
        assertThat(markdown).hasStatus(HttpStatus.OK).hasContentTypeCompatibleWith("text/markdown");
        assertThat(new String(markdown.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .startsWith("## Evaluation of run").contains("| Stage | Score | Weakest criterion |");

        assertThat(mvc.get().uri("/api/v1/runs/" + id + "/evaluation?format=pdf").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/runs/no-such-run/evaluation").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theSuiteRunsOnIsolatedEnginesAndLeavesTheProductionStoreUntouched() {
        MvcTestResult result = mvc.post().uri("/api/v1/evaluation/suite/run")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode suite = body(result);
        assertThat(suite.path("suite").asString()).isEqualTo("default");
        assertThat(suite.path("provider").asString()).isEqualTo("deterministic");
        assertThat(suite.path("total").asInt()).isEqualTo(suite.path("cases").size()).isGreaterThan(5);
        assertThat(suite.path("passed").asBoolean()).as(suite.path("markdown").asString()).isTrue();
        assertThat(suite.path("passedCount").asInt()).isEqualTo(suite.path("total").asInt());
        assertThat(suite.path("cases").get(0).path("report").path("overall").asDouble()).isPositive();
        assertThat(suite.path("markdown").asString()).contains("cases passed");

        List<String> suiteRunIds = new ArrayList<>();
        suite.path("cases").forEach(c -> {
            if (!c.path("runId").isNull()) {
                suiteRunIds.add(c.path("runId").asString());
            }
        });
        assertThat(suiteRunIds).hasSize(suite.path("total").asInt());
        for (String runId : suiteRunIds) {
            assertThat(runs.findById(runId)).as("run %s in the production store", runId).isEmpty();
            assertThat(auditEvents.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, 0L))
                    .as("audit events of %s in the production trail", runId).isEmpty();
            assertThatThrownBy(() -> engine.get(runId)).as("run %s in the production engine", runId)
                    .isInstanceOf(RunNotFoundException.class);
        }
        assertThat(engine.list()).noneSatisfy(run -> assertThat(run.scenarioKey()).startsWith("eval:"));
    }

    @Test
    void onlyTheDeterministicProviderIsAccepted() {
        MvcTestResult result = mvc.post().uri("/api/v1/evaluation/suite/run?provider=anthropic")
                .header(OperatorIdentityResolver.HEADER, OPERATOR).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.INVALID_ARGUMENT.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("offline job");
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
