package com.agenticsdlc.orchestrator.core.json;

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
 * (tolerant reader for older snapshots and LLM outputs).</p>
 *
 * <p>All methods throw the unchecked {@link JacksonException} on malformed input.</p>
 */
public final class JsonCodec {

    private static final JsonCodec STANDARD = new JsonCodec();

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
