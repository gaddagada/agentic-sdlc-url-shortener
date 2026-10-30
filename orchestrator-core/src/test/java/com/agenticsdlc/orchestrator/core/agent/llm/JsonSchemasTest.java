package com.agenticsdlc.orchestrator.core.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

class JsonSchemasTest {

    enum Colour { RED, GREEN }

    record Inner(String name, double weight) {
    }

    record Sample(String text, int count, long total, boolean flag, Colour colour, List<String> tags,
                  List<Inner> inners, Map<String, String> labels, @Nullable Inner optional, List<List<String>> groups) {
    }

    record Recursive(String name, List<Recursive> children) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void derivesStrictObjectSchemas() {
        Map<String, Object> schema = JsonSchemas.forRecord(Sample.class);
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat((List<String>) schema.get("required")).containsExactly("text", "count", "total", "flag", "colour",
                "tags", "inners", "labels", "optional", "groups");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.get("text")).isEqualTo(Map.of("type", "string"));
        assertThat(properties.get("count")).isEqualTo(Map.of("type", "integer"));
        assertThat(properties.get("total")).isEqualTo(Map.of("type", "integer"));
        assertThat(properties.get("flag")).isEqualTo(Map.of("type", "boolean"));
        assertThat(properties.get("colour")).isEqualTo(Map.of("type", "string", "enum", List.of("RED", "GREEN")));
        assertThat(properties.get("tags")).isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
        assertThat(properties.get("labels")).isEqualTo(Map.of("type", "object", "additionalProperties",
                Map.of("type", "string")));
        Map<String, Object> inners = (Map<String, Object>) properties.get("inners");
        Map<String, Object> inner = (Map<String, Object>) inners.get("items");
        assertThat(inner).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat((Map<String, Object>) inner.get("properties")).containsEntry("weight", Map.of("type", "number"));
        Map<String, Object> optional = (Map<String, Object>) properties.get("optional");
        assertThat((List<Object>) optional.get("anyOf")).hasSize(2).contains(Map.of("type", "null"));
        assertThat(properties.get("groups")).isEqualTo(Map.of("type", "array", "items",
                Map.of("type", "array", "items", Map.of("type", "string"))));
    }

    @Test
    void everyPayloadTypeHasASchema() {
        for (ArtifactKind kind : ArtifactKind.values()) {
            Map<String, Object> schema = JsonSchemas.forRecord(kind.payloadType());
            assertThat(schema).containsEntry("type", "object");
            assertThat(JsonCodec.standard().toJson(schema)).isNotBlank();
        }
        assertThat(JsonCodec.standard().toJson(JsonSchemas.forRecord(VerificationReport.class)))
                .contains("\"build\":{\"anyOf\"");
        assertThat(JsonCodec.standard().toJson(JsonSchemas.forRecord(TestPlan.class)))
                .contains("\"coverageMatrix\":{\"additionalProperties\":{\"type\":\"string\"},\"type\":\"object\"}");
    }

    @Test
    void rejectsNonRecordsAndRecursiveRecords() {
        assertThatThrownBy(() -> JsonSchemas.forRecord(String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonSchemas.forRecord(Recursive.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recursive");
    }
}
