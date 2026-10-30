package com.agenticsdlc.orchestrator.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.model.RunBudget;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.util.Hashes;

import tools.jackson.core.JacksonException;

class JsonCodecTest {

    private final JsonCodec codec = JsonCodec.standard();

    @Test
    void canonicalHashIsStableAcrossKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", Map.of("y", 2, "x", List.of(1, 2)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("x", List.of(1, 2), "y", 2));
        b.put("b", 1);
        assertThat(codec.canonicalJson(a)).isEqualTo("{\"a\":{\"x\":[1,2],\"y\":2},\"b\":1}");
        assertThat(codec.hash(a)).isEqualTo(codec.hash(b)).hasSize(64);
        assertThat(codec.hash(a)).isEqualTo(Hashes.sha256Hex(codec.canonicalJson(a)));
        assertThat(codec.canonicalizeJson("{ \"b\": 1, \"a\": {\"y\":2, \"x\":[1,2]} }")).isEqualTo(codec.canonicalJson(a));
        assertThat(codec.hash(codec.readTree("{\"b\":1,\"a\":{\"y\":2,\"x\":[1,2]}}"))).isEqualTo(codec.hash(a));
    }

    @Test
    void recordPropertiesAreSortedAlphabetically() {
        String json = codec.canonicalJson(Payloads.design(RiskLevel.LOW));
        assertThat(json.indexOf("\"alternativesConsidered\"")).isLessThan(json.indexOf("\"components\""));
        assertThat(json.indexOf("\"components\"")).isLessThan(json.indexOf("\"overview\""));
        assertThat(json).contains("\"isNew\":true").doesNotContain("\"new\":");
    }

    @Test
    void parsesEveryPayloadByKind() {
        for (AgentRole role : AgentRole.values()) {
            ArtifactPayload payload = Payloads.forRole(role);
            ArtifactPayload parsed = codec.parsePayload(payload.kind(), codec.toJson(payload));
            assertThat(parsed).isEqualTo(payload);
            assertThat(codec.hash(parsed)).isEqualTo(codec.hash(payload));
        }
        DesignSpec design = codec.parsePayload(codec.toJson(Payloads.design(RiskLevel.HIGH)), DesignSpec.class);
        assertThat(design.risk().level()).isEqualTo(RiskLevel.HIGH);
        assertThat(ArtifactKind.forPayloadType(DesignSpec.class)).isEqualTo(ArtifactKind.DESIGN_SPEC);
    }

    @Test
    void writesTimesAsIsoStringsAndToleratesUnknownProperties() {
        assertThat(codec.toJson(Instant.parse("2026-01-01T10:00:00Z"))).isEqualTo("\"2026-01-01T10:00:00Z\"");
        assertThat(codec.toJson(RunBudget.DEFAULT)).contains("\"maxActiveDuration\":\"PT2H\"");
        assertThat(codec.fromJson(codec.toJson(RunBudget.DEFAULT), RunBudget.class)).isEqualTo(RunBudget.DEFAULT);
        RunBudget parsed = codec.fromJson("{\"maxFeedbackLoops\":1,\"maxReplans\":1,\"maxAgentCalls\":5,"
                + "\"maxLlmTokens\":10,\"maxActiveDuration\":\"PT1M\",\"somethingNew\":true}", RunBudget.class);
        assertThat(parsed.maxActiveDuration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(codec.toPrettyJson(Map.of("a", 1))).contains("\n");
    }

    @Test
    void enumValuesAreReadCaseInsensitivelyAndWrittenInUpperCase() {
        String llmOutput = """
                {"id": "NFR-1", "text": "p95 under 50 ms", "priority": "must", "category": "performance",
                 "measurable": true}
                """;

        RequirementSpec.Requirement viaText = codec.fromJson(llmOutput, RequirementSpec.Requirement.class);
        RequirementSpec.Requirement viaTree = codec.mapper().treeToValue(codec.readTree(llmOutput),
                RequirementSpec.Requirement.class);

        assertThat(viaText.category()).isEqualTo(RequirementSpec.Category.PERFORMANCE);
        assertThat(viaText.priority()).isEqualTo(RequirementSpec.Priority.MUST);
        assertThat(viaTree).isEqualTo(viaText);
        assertThat(codec.toJson(viaText)).contains("\"category\":\"PERFORMANCE\"")
                .contains("\"priority\":\"MUST\"");
        assertThat(codec.fromJson("\"Denial_Of_Service\"", SecurityReview.StrideCategory.class))
                .isEqualTo(SecurityReview.StrideCategory.DENIAL_OF_SERVICE);
        assertThat(codec.fromJson("\"n_a\"", ReleaseReadiness.ChecklistStatus.class))
                .isEqualTo(ReleaseReadiness.ChecklistStatus.N_A);
        assertThatThrownBy(() -> codec.fromJson("\"latency\"", RequirementSpec.Category.class))
                .as("values outside the enum are still rejected").isInstanceOf(JacksonException.class);
    }

    @Test
    void malformedJsonThrowsAnUncheckedJacksonException() {
        assertThatThrownBy(() -> codec.fromJson("{not json", Map.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void missingCollectionsAreNormalizedToEmpty() {
        DesignSpec parsed = codec.parsePayload("{\"overview\":\"o\"}", DesignSpec.class);
        assertThat(parsed.components()).isEmpty();
        assertThat(parsed.apiChanges()).isEmpty();
    }
}
