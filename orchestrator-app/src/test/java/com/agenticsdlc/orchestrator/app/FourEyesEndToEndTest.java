package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four-eyes rule ({@code sdlc.governance.four-eyes=true}) on the real control plane: the release of a run can
 * be approved neither by the operator who started it nor by the operator who approved its design.
 */
@SpringBootTest(properties = "sdlc.governance.four-eyes=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FourEyesEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void theReleaseNeedsAThirdOperator() {
        MvcTestResult started = mvc.post().uri("/api/v1/scenarios/greenfield/run")
                .header(OperatorIdentityResolver.HEADER, "alice").exchange();
        assertThat(started).hasStatus(HttpStatus.CREATED);
        String id = body(started).path("id").asString();

        awaitRun(id, r -> hasPendingApproval(r, "design"));
        assertThat(approve(id, "design", "bob")).hasStatus(HttpStatus.OK);
        awaitRun(id, r -> hasPendingApproval(r, "release-readiness"));

        for (String refused : new String[] {"alice", "Alice", "bob"}) {
            MvcTestResult result = approve(id, "release-readiness", refused);
            assertThat(result).as(refused).hasStatus(HttpStatus.CONFLICT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.type")
                    .isEqualTo(ProblemTypes.FOUR_EYES_VIOLATION.toString());
        }
        assertThat(approve(id, "release-readiness", "carol")).hasStatus(HttpStatus.OK);

        JsonNode done = awaitRun(id, r -> "COMPLETED".equals(r.path("status").asString()));
        assertThat(done.path("createdBy").asString()).isEqualTo("alice");
        assertThat(done.path("counters").path("approvalsGranted").asInt()).isEqualTo(2);
    }

    private MvcTestResult approve(String id, String stageId, String operator) {
        return mvc.post().uri("/api/v1/runs/" + id + "/stages/" + stageId + "/approve")
                .header(OperatorIdentityResolver.HEADER, operator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"approved by " + operator + "\"}").exchange();
    }

    private JsonNode awaitRun(String id, Predicate<JsonNode> condition) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> {
            MvcTestResult result = mvc.get().uri("/api/v1/runs/" + id).exchange();
            JsonNode run = body(result);
            last.set(run);
            return condition.test(run);
        });
        return last.get();
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
}
