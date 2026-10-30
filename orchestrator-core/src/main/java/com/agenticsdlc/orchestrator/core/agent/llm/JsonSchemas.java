package com.agenticsdlc.orchestrator.core.agent.llm;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Derives JSON schemas from records for LLM structured outputs: records become objects whose properties are
 * all required with {@code additionalProperties: false}; enums become string enums; lists and sets become
 * arrays; {@code Map<String, String>} becomes an object with string additional properties; JSpecify
 * {@code @Nullable} components accept {@code null}.
 */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    /**
     * Returns the JSON schema of a record type.
     *
     * @param type a record class
     * @return the schema as nested maps and lists (insertion ordered)
     * @throws IllegalArgumentException when the type is not a record or contains unsupported types
     */
    public static Map<String, Object> forRecord(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        return objectSchema(type, new HashSet<>());
    }

    private static Map<String, Object> objectSchema(Class<?> type, Set<Class<?>> visiting) {
        if (!visiting.add(type)) {
            throw new IllegalArgumentException("recursive record types are not supported: " + type.getName());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            AnnotatedType annotated = component.getAnnotatedType();
            Map<String, Object> schema = schemaFor(component.getGenericType(), annotated, visiting);
            if (annotated.isAnnotationPresent(Nullable.class)) {
                Map<String, Object> nullable = new LinkedHashMap<>();
                nullable.put("anyOf", List.of(schema, Map.of("type", "null")));
                schema = nullable;
            }
            properties.put(component.getName(), schema);
            required.add(component.getName());
        }
        visiting.remove(type);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> schemaFor(Type type, @Nullable AnnotatedType annotated, Set<Class<?>> visiting) {
        Class<?> raw = raw(type);
        Map<String, Object> schema = new LinkedHashMap<>();
        if (raw == String.class || raw == CharSequence.class) {
            schema.put("type", "string");
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class) {
            schema.put("type", "integer");
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == java.math.BigDecimal.class) {
            schema.put("type", "number");
        } else if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
        } else if (raw.isEnum()) {
            schema.put("type", "string");
            schema.put("enum", Arrays.stream(raw.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList());
        } else if (Collection.class.isAssignableFrom(raw)) {
            Type element = typeArgument(type, 0);
            AnnotatedType annotatedElement = annotatedArgument(annotated, 0);
            schema.put("type", "array");
            schema.put("items", schemaFor(element, annotatedElement, visiting));
            if (Set.class.isAssignableFrom(raw)) {
                schema.put("uniqueItems", true);
            }
        } else if (Map.class.isAssignableFrom(raw)) {
            if (raw(typeArgument(type, 0)) != String.class || raw(typeArgument(type, 1)) != String.class) {
                throw new IllegalArgumentException("only Map<String, String> is supported: " + type);
            }
            schema.put("type", "object");
            schema.put("additionalProperties", Map.of("type", "string"));
        } else if (raw.isRecord()) {
            return objectSchema(raw, visiting);
        } else {
            throw new IllegalArgumentException("unsupported type in structured output: " + type);
        }
        return schema;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            return c;
        }
        throw new IllegalArgumentException("unsupported type in structured output: " + type);
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        throw new IllegalArgumentException("raw collection types are not supported: " + type);
    }

    private static @Nullable AnnotatedType annotatedArgument(@Nullable AnnotatedType annotated, int index) {
        if (annotated instanceof AnnotatedParameterizedType p && p.getAnnotatedActualTypeArguments().length > index) {
            return p.getAnnotatedActualTypeArguments()[index];
        }
        return null;
    }
}
