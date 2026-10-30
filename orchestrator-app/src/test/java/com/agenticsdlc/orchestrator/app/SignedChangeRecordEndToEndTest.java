package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Signed change records end to end with {@code sdlc.governance.signing-key}: a deterministic greenfield run driven
 * through the REST API completes with an HMAC-signed release change record that verifies, and editing the approvals
 * of that record in the stored run snapshot (what someone with database access could do) makes the same view
 * report {@code signatureVerified: false}. A registry retention of zero makes the engine read the run from the
 * database again once it is idle.
 */
@SpringBootTest(properties = {"sdlc.governance.signing-key=base64:"
        + "c2lnbmVkLWNoYW5nZS1yZWNvcmQtZTJlLXRlc3Qta2V5", "sdlc.governance.signing-key-id=e2e-key",
    "sdlc.engine.registry-retention=0s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignedChangeRecordEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OrchestrationEngine engine;

    @Test
    void theCompletedReleaseIsSignedAndTamperedApprovalsNoLongerVerify() {
        MvcTestResult started = mvc.post().uri("/api/v1/scenarios/greenfield/run")
                .header(OperatorIdentityResolver.HEADER, "alice").exchange();
        assertThat(started).hasStatus(HttpStatus.CREATED);
        String id = body(started).path("id").asString();
        awaitRun(id, r -> pending(r, "design"));
        approve(id, "design", "bob");
        awaitRun(id, r -> pending(r, "release-readiness"));
        JsonNode beforeRelease = get("/api/v1/runs/" + id + "/artifacts/release-readiness?version=1");
        assertThat(beforeRelease.path("signatureVerified").isNull() || beforeRelease.path("signatureVerified")
                .isMissingNode()).as("the agent's record is not finalized, so it is unsigned").isTrue();
        approve(id, "release-readiness", "carol");
        awaitRun(id, r -> "COMPLETED".equals(r.path("status").asString()));

        JsonNode release = get("/api/v1/runs/" + id + "/artifacts/release-readiness");
        assertThat(release.path("signature").path("algorithm").asString()).isEqualTo("HmacSHA256");
        assertThat(release.path("signature").path("keyId").asString()).isEqualTo("e2e-key");
        assertThat(release.path("signature").path("value").asString()).hasSize(64);
        assertThat(release.path("signatureVerified").asBoolean()).isTrue();
        assertThat(release.path("payload").path("changeRecord").path("approvals").findValuesAsString("approver"))
                .containsExactly("bob", "carol");
        assertThat(get("/api/v1/runs/" + id + "/audit/integrity").path("valid").asBoolean()).isTrue();

        tamperWithTheReleaseApprovals(id, "mallory");
        engine.evictIdleRuns();

        JsonNode tampered = get("/api/v1/runs/" + id + "/artifacts/release-readiness");
        assertThat(tampered.path("payload").path("changeRecord").path("approvals").findValuesAsString("approver"))
                .contains("mallory");
        assertThat(tampered.path("signature").path("keyId").asString()).isEqualTo("e2e-key");
        assertThat(tampered.path("signatureVerified").asBoolean()).isFalse();
    }

    /** Rewrites the approvers of the finalized release record inside the stored run snapshot. */
    private void tamperWithTheReleaseApprovals(String id, String approver) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String snapshot = jdbc.sql("SELECT snapshot_json FROM runs WHERE id = ?").param(id).query(String.class)
                .single();
        ObjectNode root = (ObjectNode) json.readTree(snapshot);
        ObjectNode latest = null;
        for (JsonNode artifact : (ArrayNode) root.path("artifacts")) {
            if ("release-readiness".equals(artifact.path("stageId").asString())
                    && (latest == null || artifact.path("version").asInt() > latest.path("version").asInt())) {
                latest = (ObjectNode) artifact;
            }
        }
        assertThat(latest).isNotNull();
        ObjectNode payload = (ObjectNode) json.readTree(latest.path("payloadJson").asString());
        for (JsonNode approval : (ArrayNode) payload.path("changeRecord").path("approvals")) {
            ((ObjectNode) approval).put("approver", approver);
        }
        latest.put("payloadJson", json.writeValueAsString(payload));
        int updated = jdbc.sql("UPDATE runs SET snapshot_json = ? WHERE id = ?")
                .params(json.writeValueAsString(root), id).update();
        assertThat(updated).isOne();
    }

    private void approve(String id, String stageId, String operator) {
        assertThat(mvc.post().uri("/api/v1/runs/" + id + "/stages/" + stageId + "/approve")
                .header(OperatorIdentityResolver.HEADER, operator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"approved by " + operator + "\"}").exchange()).hasStatus(HttpStatus.OK);
    }

    private JsonNode awaitRun(String id, Predicate<JsonNode> condition) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> {
            JsonNode run = get("/api/v1/runs/" + id);
            last.set(run);
            return condition.test(run);
        });
        return last.get();
    }

    private static boolean pending(JsonNode run, String stageId) {
        for (JsonNode action : run.path("pendingActions")) {
            if ("APPROVAL".equals(action.path("type").asString())
                    && stageId.equals(action.path("stageId").asString())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode get(String uri) {
        MvcTestResult result = mvc.get().uri(uri).exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return body(result);
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
