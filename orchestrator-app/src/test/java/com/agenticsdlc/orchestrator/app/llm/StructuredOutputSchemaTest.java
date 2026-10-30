package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
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
