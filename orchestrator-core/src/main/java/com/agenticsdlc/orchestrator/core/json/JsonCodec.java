package com.agenticsdlc.orchestrator.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.util.Hashes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 based JSON codec used for persistence snapshots, canonical hashing and typed payload parsing.
 *
 * <p>The underlying mapper sorts properties alphabetically and orders map entries by key, so
 * {@link #toJson(Object)} output is already canonical: equal values always produce byte-identical JSON.
 * Instants and durations are written as ISO-8601 strings. Unknown properties are tolerated when reading
 * (tolerant reader for older snapshots and LLM outputs), and enum values are read case-insensitively (models
 * sometimes answer {@code "performance"} for {@code PERFORMANCE}); enums are always written by their exact
 * upper-case constant name, so canonical JSON and hashes are unaffected.</p>
 *
 * <p>All methods throw the unchecked {@link JacksonException} on malformed input.</p>
 */
public final class JsonCodec {

    private static final JsonCodec STANDARD = new JsonCodec();

    /** Maximum nesting depth accepted by {@link #strictCanonicalJson(Object)} (guards against cyclic values). */
    public static final int MAX_CANONICAL_DEPTH = 256;

    private final JsonMapper mapper;

    /**
     * Creates a codec with the standard mapper configuration ({@link #standardMapperBuilder()}).
     */
    public JsonCodec() {
        this(standardMapperBuilder().build());
    }

    /**
     * Creates a codec around a caller supplied mapper. The mapper should be built from
     * {@link #standardMapperBuilder()} so that hashing stays canonical.
     *
     * @param mapper the mapper
     */
    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns a shared codec with the standard configuration. The instance is immutable and thread-safe.
     *
     * @return the shared codec
     */
    public static JsonCodec standard() {
        return STANDARD;
    }

    /**
     * Returns a mapper builder with the canonical configuration used by this module.
     *
     * @return a new builder
     */
    public static JsonMapper.Builder standardMapperBuilder() {
        return JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                // records are creator-based: without this, their properties keep declaration order
                .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
                // tolerant reader: LLM outputs may spell enum constants in lower or mixed case
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Returns the underlying mapper.
     *
     * @return the mapper
     */
    public JsonMapper mapper() {
        return mapper;
    }

    /**
     * Serializes {@code value} to compact JSON (canonical ordering).
     *
     * @param value the value
     * @return JSON text
     */
    public String toJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    /**
     * Serializes {@code value} to indented JSON.
     *
     * @param value the value
     * @return pretty JSON text
     */
    public String toPrettyJson(Object value) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    /**
     * Parses JSON into the given type.
     *
     * @param json JSON text
     * @param type target type
     * @param <T> target type
     * @return the parsed value
     */
    public <T> T fromJson(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    /**
     * Parses JSON into a tree.
     *
     * @param json JSON text
     * @return the tree
     */
    public JsonNode readTree(String json) {
        return mapper.readTree(json);
    }

    /**
     * Converts a value to a JSON tree.
     *
     * @param value the value
     * @return the tree
     */
    public JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    /**
     * Converts a value (for example a map or tree) to the given type.
     *
     * @param value the source value
     * @param type the target type
     * @param <T> the target type
     * @return the converted value
     */
    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    /**
     * Returns the canonical JSON of {@code value}: properties sorted alphabetically, map entries sorted by
     * key, no insignificant whitespace. JSON trees are normalized the same way.
     *
     * @param value the value (a record, map, list, scalar or {@link JsonNode})
     * @return canonical JSON text
     */
    public String canonicalJson(Object value) {
        Object normalized = value instanceof JsonNode node ? mapper.convertValue(node, Object.class) : value;
        return mapper.writeValueAsString(normalized);
    }

    /**
     * Returns the strict canonical JSON of a plain JSON value tree, independent of the Java types that hold it, so
     * that a value hashes identically before and after a round trip through any JSON store. Used for audit hash
     * chaining and change record signatures, where {@link #canonicalJson(Object)} would depend on how a store's
     * mapper types numbers and map keys.
     *
     * <p>Rules (in the spirit of RFC 8785):</p>
     * <ul>
     *   <li>objects: keys are the {@code toString()} of the map keys (an enum key is its name), sorted by UTF-16 code
     *       units at every nesting level; a null key or two keys with the same text are rejected;</li>
     *   <li>no insignificant whitespace;</li>
     *   <li>strings: quotation mark and backslash are escaped, control characters below U+0020 use the short escapes
     *       (backspace, form feed, line feed, carriage return, tab) or a lowercase six-character unicode escape;
     *       every other character is written as is (the caller encodes the text as UTF-8);</li>
     *   <li>numbers are written in a normalized decimal form whatever their Java type: integral values without a
     *       fraction or exponent ({@code 2}, {@code 2L}, {@code 2.0} and {@code 2E0} all become {@code 2}), other
     *       values as a plain decimal without trailing zeros ({@code 0.5}); a {@code float} uses its shortest
     *       decimal representation ({@code 0.1f} becomes {@code 0.1}); NaN and infinities become the strings
     *       {@code "NaN"}, {@code "Infinity"} and {@code "-Infinity"} (which is what a JSON round trip yields);</li>
     *   <li>booleans and nulls as literals; lists, sets and arrays as arrays in iteration order; enums by name;</li>
     *   <li>any other value (records, instants, JSON trees, ...) is first converted to a plain tree with this codec's
     *       mapper, so it is written exactly as it reads back after a JSON round trip (for example an
     *       {@link java.time.Instant} becomes its ISO-8601 string).</li>
     * </ul>
     *
     * @param value the value (may be null)
     * @return canonical JSON text
     * @throws IllegalArgumentException for null or duplicate map keys, or values nested deeper than
     *                                  {@value #MAX_CANONICAL_DEPTH} levels
     */
    public String strictCanonicalJson(@Nullable Object value) {
        StringBuilder out = new StringBuilder();
        writeCanonical(value, out, 0);
        return out.toString();
    }

    private void writeCanonical(@Nullable Object value, StringBuilder out, int depth) {
        if (depth > MAX_CANONICAL_DEPTH) {
            throw new IllegalArgumentException("value is nested deeper than " + MAX_CANONICAL_DEPTH + " levels");
        }
        switch (value) {
            case null -> out.append("null");
            case CharSequence text -> writeCanonicalString(text.toString(), out);
            case Boolean bool -> out.append(bool.booleanValue());
            case Number number -> writeCanonicalNumber(number, out);
            case Character character -> writeCanonicalString(character.toString(), out);
            case Enum<?> constant -> writeCanonicalString(constant.name(), out);
            case JsonNode node -> writeCanonical(mapper.convertValue(node, Object.class), out, depth + 1);
            case Map<?, ?> map -> writeCanonicalObject(map, out, depth);
            case Iterable<?> items -> writeCanonicalArray(items, out, depth);
            case Object[] items -> writeCanonicalArray(Arrays.asList(items), out, depth);
            default -> {
                Object plain = mapper.convertValue(value, Object.class);
                if (plain == null || plain.getClass() == value.getClass()) {
                    writeCanonicalString(String.valueOf(value), out);
                } else {
                    writeCanonical(plain, out, depth + 1);
                }
            }
        }
    }

    private void writeCanonicalObject(Map<?, ?> map, StringBuilder out, int depth) {
        TreeMap<String, @Nullable Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException("canonical JSON objects cannot have a null key");
            }
            String name = key instanceof Enum<?> constant ? constant.name() : key.toString();
            if (sorted.containsKey(name)) {
                throw new IllegalArgumentException("duplicate key in canonical JSON object: " + name);
            }
            sorted.put(name, entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, @Nullable Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeCanonicalString(entry.getKey(), out);
            out.append(':');
            writeCanonical(entry.getValue(), out, depth + 1);
        }
        out.append('}');
    }

    private void writeCanonicalArray(Iterable<?> items, StringBuilder out, int depth) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeCanonical(item, out, depth + 1);
        }
        out.append(']');
    }

    private static void writeCanonicalNumber(Number number, StringBuilder out) {
        BigDecimal decimal;
        switch (number) {
            case Integer i -> {
                out.append(i.intValue());
                return;
            }
            case Long l -> {
                out.append(l.longValue());
                return;
            }
            case Short s -> {
                out.append(s.shortValue());
                return;
            }
            case Byte b -> {
                out.append(b.byteValue());
                return;
            }
            case BigInteger big -> {
                out.append(big);
                return;
            }
            case BigDecimal big -> decimal = big;
            case Double d -> {
                if (!Double.isFinite(d)) {
                    writeCanonicalString(d.toString(), out);
                    return;
                }
                decimal = new BigDecimal(Double.toString(d));
            }
            case Float f -> {
                if (!Float.isFinite(f)) {
                    writeCanonicalString(f.toString(), out);
                    return;
                }
                decimal = new BigDecimal(Float.toString(f));
            }
            default -> {
                try {
                    decimal = new BigDecimal(number.toString());
                } catch (NumberFormatException e) {
                    writeCanonicalString(number.toString(), out);
                    return;
                }
            }
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        out.append(stripped.scale() <= 0 ? stripped.toBigIntegerExact().toString() : stripped.toPlainString());
    }

    private static void writeCanonicalString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Re-serializes arbitrary JSON text canonically.
     *
     * @param json JSON text
     * @return canonical JSON text
     */
    public String canonicalizeJson(String json) {
        return canonicalJson(mapper.readTree(json));
    }

    /**
     * Returns the SHA-256 hex digest of the canonical JSON of {@code value}.
     *
     * @param value the value
     * @return 64 lowercase hex characters
     */
    public String hash(Object value) {
        return Hashes.sha256Hex(canonicalJson(value));
    }

    /**
     * Parses a payload of the given artifact kind; each kind knows its payload record class.
     *
     * @param kind the artifact kind
     * @param json payload JSON
     * @return the typed payload
     */
    public ArtifactPayload parsePayload(ArtifactKind kind, String json) {
        return mapper.readValue(json, kind.payloadType());
    }

    /**
     * Parses a payload into an explicit payload type.
     *
     * @param json payload JSON
     * @param type payload record class
     * @param <T> payload type
     * @return the typed payload
     */
    public <T extends ArtifactPayload> T parsePayload(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
