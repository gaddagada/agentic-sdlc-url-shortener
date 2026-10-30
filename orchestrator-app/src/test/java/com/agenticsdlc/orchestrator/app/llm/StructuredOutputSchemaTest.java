package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

import tools.jackson.databind.JsonNode;

/**
 * {@link StructuredOutputSchema} produces schemas inside the structured-output subset for every artifact payload
 * and restores entry-array maps.
 */
class StructuredOutputSchemaTest {

    private static final JsonCodec CODEC = JsonCodec.standard();

    @ParameterizedTest
    @EnumSource(ArtifactKind.class)
    void everyPayloadSchemaUsesOnlyClosedObjectsAndNoArrayConstraints(ArtifactKind kind) {
        Map<String, Object> schema = StructuredOutputSchema.forRecord(kind.payloadType()).schema();

        assertThat(schema).containsEntry("type", "object");
        assertSupported(schema, kind.name());
    }

    @Test
    void closedValueSetsAreSentAsUpperCaseStringEnums() {
        assertThat(enumOf(ArtifactKind.REQUIREMENT_SPEC, "nonFunctional", "category"))
                .isEqualTo(names(RequirementSpec.Category.values()))
                .containsExactly("FUNCTIONAL", "PERFORMANCE", "RELIABILITY", "SECURITY", "COMPLIANCE", "OPERABILITY",
                        "USABILITY", "OTHER");
        assertThat(enumOf(ArtifactKind.REQUIREMENT_SPEC, "functional", "category"))
                .isEqualTo(names(RequirementSpec.Category.values()));
        assertThat(enumOf(ArtifactKind.IMPACT_ANALYSIS, "components", "type"))
                .isEqualTo(names(ImpactAnalysis.ComponentType.values()))
                .containsExactly("CONTROLLER", "SERVICE", "REPOSITORY", "ENTITY", "CONFIG", "FILTER", "TEST",
                        "RESOURCE", "OTHER");
        assertThat(enumOf(ArtifactKind.RELEASE_READINESS, "checklist", "status"))
                .containsExactly("PASS", "FAIL", "WAIVED", "N_A");
        assertThat(enumOf(ArtifactKind.SECURITY_REVIEW, "threats", "category"))
                .isEqualTo(names(SecurityReview.StrideCategory.values()))
                .containsExactly("SPOOFING", "TAMPERING", "REPUDIATION", "INFORMATION_DISCLOSURE", "DENIAL_OF_SERVICE",
                        "ELEVATION_OF_PRIVILEGE");
        assertThat(enumOf(ArtifactKind.SECURITY_REVIEW, "threats", "status"))
                .isEqualTo(names(SecurityReview.ThreatStatus.values()))
                .containsExactly("MITIGATED", "ACCEPTED", "OPEN");
        assertThat(names(ReleaseReadiness.ChecklistStatus.values())).containsExactly("PASS", "FAIL", "WAIVED", "N_A");
    }

    @Test
    void mapsBecomeEntryArraysAndAreRestored() {
        StructuredOutputSchema schema = StructuredOutputSchema.forRecord(ArtifactKind.TEST_PLAN.payloadType());
        assertThat(schema.hasMaps()).isTrue();
        assertThat(StructuredOutputSchema.forRecord(ArtifactKind.DOCUMENTATION_BUNDLE.payloadType()).hasMaps())
                .isFalse();

        JsonNode restored = schema.restore(CODEC.readTree("""
                {"strategy": "s", "levels": [], "cases": [],
                 "coverageMatrix": [{"key": "FR-1", "value": "TC-1, TC-2"}, {"key": "NFR-1", "value": ""}],
                 "risksNotCovered": ["none"]}
                """));

        assertThat(restored.path("coverageMatrix").isObject()).isTrue();
        assertThat(restored.path("coverageMatrix").path("FR-1").asString()).isEqualTo("TC-1, TC-2");
        assertThat(restored.path("coverageMatrix").path("NFR-1").asString()).isEmpty();
        assertThat(restored.path("risksNotCovered").get(0).asString()).isEqualTo("none");
    }

    @Test
    void outputThatAlreadyUsesObjectsIsLeftUntouched() {
        StructuredOutputSchema schema = StructuredOutputSchema.forRecord(ArtifactKind.TEST_PLAN.payloadType());

        JsonNode restored = schema.restore(CODEC.readTree("{\"coverageMatrix\": {\"FR-1\": \"TC-1\"}}"));

        assertThat(restored.path("coverageMatrix").path("FR-1").asString()).isEqualTo("TC-1");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> enumOf(ArtifactKind kind, String arrayProperty, String field) {
        Map<String, Object> schema = StructuredOutputSchema.forRecord(kind.payloadType()).schema();
        Map<String, Object> array = (Map<String, Object>) ((Map<String, Object>) schema.get("properties"))
                .get(arrayProperty);
        Map<String, Object> item = (Map<String, Object>) array.get("items");
        Map<String, Object> property = (Map<String, Object>) ((Map<String, Object>) item.get("properties")).get(field);
        assertThat(property).as(kind + "." + arrayProperty + "[]." + field).containsEntry("type", "string");
        return (List<Object>) property.get("enum");
    }

    private static List<Object> names(Enum<?>[] constants) {
        return Arrays.stream(constants).<Object>map(Enum::name).toList();
    }

    private static void assertSupported(Object node, String path) {
        if (node instanceof Map<?, ?> map) {
            assertThat(map.containsKey("uniqueItems")).as(path + " uniqueItems").isFalse();
            if ("object".equals(map.get("type"))) {
                assertThat(map.get("additionalProperties")).as(path + " additionalProperties").isEqualTo(false);
            }
            map.forEach((key, value) -> assertSupported(value, path + "." + key));
        } else if (node instanceof List<?> list) {
            list.forEach(item -> assertSupported(item, path + "[]"));
        }
    }
}
