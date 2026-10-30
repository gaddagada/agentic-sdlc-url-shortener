package com.agenticsdlc.orchestrator.app.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.llm.JsonSchemas;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapts the JSON schema of a payload record ({@link JsonSchemas#forRecord(Class)}) to the subset accepted by
 * Anthropic structured outputs, and maps the model's output back to the record's JSON shape.
 *
 * <p>Anthropic requires {@code additionalProperties: false} on every object and does not support array
 * constraints such as {@code uniqueItems}. Free-form {@code Map<String, String>} members (for example
 * {@code TestPlan.coverageMatrix}) are therefore requested as arrays of {@code {key, value}} entries and converted
 * back into JSON objects by {@link #restore(JsonNode)} before the payload is parsed.</p>
 */
final class StructuredOutputSchema {

    private static final String TYPE = "type";
    private static final String PROPERTIES = "properties";
    private static final String ITEMS = "items";
    private static final String ANY_OF = "anyOf";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

    private final Map<String, Object> original;
    private final Map<String, Object> adapted;
    private final boolean hasMaps;

    private StructuredOutputSchema(Map<String, Object> original) {
        this.original = original;
        boolean[] maps = new boolean[1];
        this.adapted = adaptObject(original, maps);
        this.hasMaps = maps[0];
    }

    /**
     * Builds the schema of a record type.
     *
     * @param type the record class
     * @return the schema
     */
    static StructuredOutputSchema forRecord(Class<?> type) {
        return new StructuredOutputSchema(JsonSchemas.forRecord(type));
    }

    /**
     * Returns the schema to send to Anthropic.
     *
     * @return the adapted schema (immutable view not guaranteed; do not modify)
     */
    Map<String, Object> schema() {
        return adapted;
    }

    /**
     * Returns whether the record contains free-form maps encoded as entry arrays.
     *
     * @return true when at least one map was adapted
     */
    boolean hasMaps() {
        return hasMaps;
    }

    /**
     * Converts the model output back to the shape of the original schema (entry arrays become objects).
     *
     * @param value parsed model output
     * @return the restored tree
     */
    JsonNode restore(JsonNode value) {
        return restore(value, original);
    }

    // ------------------------------------------------------------------------------------------- adaptation

    private static Object adapt(Object node, boolean[] maps) {
        if (node instanceof Map<?, ?> map) {
            return adaptObject(map, maps);
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(adapt(item, maps)));
            return copy;
        }
        return node;
    }

    private static Map<String, Object> adaptObject(Map<?, ?> schema, boolean[] maps) {
        if (isMapSchema(schema)) {
            maps[0] = true;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(TYPE, "object");
            entry.put(PROPERTIES, Map.of("key", Map.of(TYPE, "string"), "value", Map.of(TYPE, "string")));
            entry.put("required", List.of("key", "value"));
            entry.put(ADDITIONAL_PROPERTIES, false);
            Map<String, Object> array = new LinkedHashMap<>();
            array.put(TYPE, "array");
            array.put(ITEMS, entry);
            return array;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        schema.forEach((key, value) -> {
            if (!"uniqueItems".equals(key)) {
                copy.put(String.valueOf(key), adapt(value, maps));
            }
        });
        return copy;
    }

    private static boolean isMapSchema(Map<?, ?> schema) {
        return "object".equals(schema.get(TYPE)) && schema.get(ADDITIONAL_PROPERTIES) instanceof Map<?, ?>;
    }

    // ------------------------------------------------------------------------------------------- restoration

    private static JsonNode restore(@Nullable JsonNode value, Map<?, ?> schema) {
        if (value == null || value.isNull()) {
            return value == null ? JsonNodeFactory.instance.nullNode() : value;
        }
        if (schema.get(ANY_OF) instanceof List<?> branches) {
            for (Object branch : branches) {
                if (branch instanceof Map<?, ?> b && !"null".equals(b.get(TYPE))) {
                    return restore(value, b);
                }
            }
            return value;
        }
        if (isMapSchema(schema)) {
            return value.isArray() ? entriesToObject(value) : value;
        }
        if (schema.get(PROPERTIES) instanceof Map<?, ?> properties && value.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                Object fieldSchema = properties.get(field.getKey());
                copy.set(field.getKey(), fieldSchema instanceof Map<?, ?> s ? restore(field.getValue(), s)
                        : field.getValue());
            }
            return copy;
        }
        if (schema.get(ITEMS) instanceof Map<?, ?> items && value.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : value) {
                copy.add(restore(element, items));
            }
            return copy;
        }
        return value;
    }

    private static ObjectNode entriesToObject(JsonNode entries) {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        for (JsonNode entry : entries) {
            JsonNode key = entry.get("key");
            if (key != null && !key.isNull()) {
                JsonNode value = entry.get("value");
                object.set(key.asString(), value == null ? JsonNodeFactory.instance.nullNode() : value);
            }
        }
        return object;
    }
}
