package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmException;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.SseException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Unit tests of {@link AnthropicLlmClient} without network: availability, request construction, response parsing
 * (including entry-array maps) and SDK error classification.
 */
class AnthropicLlmClientTest {

    private static final JsonCodec CODEC = JsonCodec.standard();
    private static final LlmRequest REQUEST = new LlmRequest("system prompt", "user prompt", 8000,
            Duration.ofSeconds(60));
    private static final String TEST_PLAN_JSON = """
            {"strategy": "risk based", "levels": [{"level": "UNIT", "scope": "services", "tooling": "JUnit"}],
             "cases": [{"id": "TC-1", "title": "creates a link", "level": "UNIT", "type": "POSITIVE",
                        "preconditions": "none", "steps": ["post"], "expected": "201", "traces": ["FR-1"]}],
             "coverageMatrix": [{"key": "FR-1", "value": "TC-1"}, {"key": "NFR-1", "value": "TC-1"}],
             "risksNotCovered": []}
            """;
    /** A requirement spec as a model returned it in a live call: valid structure, lower-case enum constants. */
    private static final String LOWER_CASE_SPEC_JSON = """
            {"normalizedStatement": "Add a QR code endpoint.", "intent": "Add a QR code endpoint",
             "classification": "brownfield", "scopeIn": ["QR codes"], "scopeOut": [],
             "functional": [{"id": "FR-1", "text": "Return a PNG QR code of the short URL", "priority": "MUST",
                             "category": "functional", "measurable": false}],
             "nonFunctional": [{"id": "NFR-1", "text": "Respond within 200 ms at p95", "priority": "should",
                                "category": "performance", "measurable": true}],
             "acceptanceCriteria": [{"id": "AC-1", "given": "an existing short link",
                                     "when": "the QR code is requested", "then": "a PNG image is returned",
                                     "traces": ["FR-1", "NFR-1"]}],
             "assumptions": [], "openQuestions": [],
             "ambiguity": {"score": 0.1, "findings": [], "questions": []},
             "preliminaryRisk": {"level": "low", "factors": []}}
            """;

    @Test
    void availabilityRequiresAnEnabledProviderWithAKey() {
        AnthropicLlmClient withKey = client(settings(true, "sk-test"), params -> null);
        AnthropicLlmClient withoutKey = client(settings(true, " "), params -> null);
        AnthropicLlmClient disabled = client(settings(false, "sk-test"), params -> null);

        assertThat(withKey.isAvailable()).isTrue();
        assertThat(withKey.detail()).doesNotContain("sk-test");
        assertThat(withoutKey.isAvailable()).isFalse();
        assertThat(withoutKey.detail()).isEqualTo("ANTHROPIC_API_KEY not set");
        assertThat(disabled.isAvailable()).isFalse();
        assertThat(withKey.providerId()).isEqualTo("anthropic");
        assertThat(withKey.model()).isEqualTo("claude-opus-5-5");
        assertThat(settings(true, "sk-secret").toString()).doesNotContain("sk-secret");
    }

    @Test
    void completeWithoutKeyFailsWithoutCallingTheApi() {
        AtomicReference<MessageCreateParams> sent = new AtomicReference<>();
        AnthropicLlmClient client = client(settings(true, ""), params -> {
            sent.set(params);
            return null;
        });

        assertThatThrownBy(() -> client.complete(REQUEST, TestPlan.class)).isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).retryable()).isFalse());
        assertThat(sent.get()).isNull();
    }

    @Test
    void sendsAStructuredOutputRequestAndParsesTheTypedPayload() throws Exception {
        AtomicReference<MessageCreateParams> sent = new AtomicReference<>();
        AnthropicLlmClient client = client(settings(true, "sk-test"), params -> {
            sent.set(params);
            return message(TEST_PLAN_JSON, StopReason.END_TURN);
        });

        LlmResponse<TestPlan> response = client.complete(REQUEST, TestPlan.class);

        TestPlan plan = response.output();
        plan.validate();
        assertThat(plan.coverageMatrix()).containsEntry("FR-1", "TC-1").containsEntry("NFR-1", "TC-1");
        assertThat(response.usage().inputTokens()).isEqualTo(1200L);
        assertThat(response.usage().outputTokens()).isEqualTo(340L);
        assertThat(response.model()).isEqualTo("claude-opus-5-5");
        assertThat(response.rawText()).isEqualTo(TEST_PLAN_JSON);

        MessageCreateParams params = sent.get();
        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(params.maxTokens()).isEqualTo(8000L);
        JsonNode body = CODEC.readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
        assertThat(body.path("system").asString()).isEqualTo("system prompt");
        assertThat(body.path("messages").get(0).path("content").asString()).startsWith("user prompt")
                .contains("arrays of {\"key\"");
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("temperature")).isFalse();
        JsonNode format = body.path("output_config").path("format");
        assertThat(format.path("type").asString()).isEqualTo("json_schema");
        JsonNode coverage = format.path("schema").path("properties").path("coverageMatrix");
        assertThat(coverage.path("type").asString()).isEqualTo("array");
        assertThat(coverage.path("items").path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void lowerCaseEnumValuesAreParsedAndTheSchemaConstrainsThemToUpperCaseConstants() throws Exception {
        AtomicReference<MessageCreateParams> sent = new AtomicReference<>();
        AnthropicLlmClient client = client(settings(true, "sk-test"), params -> {
            sent.set(params);
            return message(LOWER_CASE_SPEC_JSON, StopReason.END_TURN);
        });

        RequirementSpec spec = client.complete(REQUEST, RequirementSpec.class).output();

        spec.validate();
        assertThat(spec.nonFunctional()).singleElement().satisfies(r -> {
            assertThat(r.category()).isEqualTo(RequirementSpec.Category.PERFORMANCE);
            assertThat(r.priority()).isEqualTo(RequirementSpec.Priority.SHOULD);
        });
        assertThat(spec.functional().getFirst().category()).isEqualTo(RequirementSpec.Category.FUNCTIONAL);
        assertThat(spec.classification()).isEqualTo(RequirementSpec.Classification.BROWNFIELD);
        assertThat(CODEC.toJson(spec)).contains("\"category\":\"PERFORMANCE\"").doesNotContain("performance");

        JsonNode body = CODEC.readTree(ObjectMappers.jsonMapper().writeValueAsString(sent.get()._body()));
        JsonNode category = body.path("output_config").path("format").path("schema").path("properties")
                .path("nonFunctional").path("items").path("properties").path("category");
        assertThat(category.path("type").asString()).isEqualTo("string");
        List<String> allowed = new ArrayList<>();
        category.path("enum").forEach(value -> allowed.add(value.asString()));
        assertThat(allowed).isEqualTo(Arrays.stream(RequirementSpec.Category.values()).map(Enum::name).toList());
    }

    @Test
    void refusalIsNotRetryableAndTruncationIs() {
        AnthropicLlmClient refusing = client(settings(true, "k"), params -> message("{}", StopReason.REFUSAL));
        AnthropicLlmClient truncating = client(settings(true, "k"),
                params -> message("{\"strategy\":", StopReason.MAX_TOKENS));

        assertThatThrownBy(() -> refusing.complete(REQUEST, TestPlan.class))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("provider refused");
                });
        assertThatThrownBy(() -> truncating.complete(REQUEST, TestPlan.class))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void unparseableOutputSurfacesAsAJacksonException() {
        AnthropicLlmClient client = client(settings(true, "k"), params -> message("not json", StopReason.END_TURN));

        assertThatThrownBy(() -> client.complete(REQUEST, TestPlan.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void sdkErrorsAreMappedThroughComplete() {
        AnthropicLlmClient client = client(settings(true, "k"), params -> {
            throw RateLimitException.builder().headers(Headers.builder().build()).body(JsonValue.from(Map.of()))
                    .build();
        });

        assertThatThrownBy(() -> client.complete(REQUEST, TestPlan.class))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("429");
                });
    }

    @Test
    void streamedEventsAreAccumulatedIntoTheFinalMessage() throws Exception {
        Message message = AnthropicLlmClient.accumulate(streamEvents(true));

        assertThat(message.stopReason()).contains(StopReason.END_TURN);
        assertThat(message.usage().inputTokens()).isEqualTo(1200L);
        assertThat(message.usage().outputTokens()).isEqualTo(340L);
        assertThat(message.content()).singleElement().satisfies(block ->
                assertThat(block.text().orElseThrow().text()).isEqualTo(TEST_PLAN_JSON));
    }

    @Test
    void aStreamedResponseIsParsedLikeANonStreamedOne() throws Exception {
        AnthropicLlmClient client = client(settings(true, "k"), params -> AnthropicLlmClient.accumulate(
                events(true)));

        LlmResponse<TestPlan> response = client.complete(REQUEST, TestPlan.class);

        assertThat(response.output().coverageMatrix()).containsEntry("FR-1", "TC-1");
        assertThat(response.usage().inputTokens()).isEqualTo(1200L);
        assertThat(response.usage().outputTokens()).isEqualTo(340L);
        assertThat(response.rawText()).isEqualTo(TEST_PLAN_JSON);
    }

    @Test
    void aStreamThatEndsBeforeMessageStopIsRetryable() {
        AnthropicLlmClient client = client(settings(true, "k"), params -> AnthropicLlmClient.accumulate(
                events(false)));

        assertThatThrownBy(() -> client.complete(REQUEST, TestPlan.class))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getCause()).isInstanceOf(AnthropicInvalidDataException.class);
                });
    }

    @Test
    void theDefaultsAllowLongStreamedOutputs() {
        LlmProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("sdlc.llm", LlmProperties.class);

        assertThat(defaults.anthropic().maxOutputTokens()).isEqualTo(64000);
        assertThat(defaults.anthropic().timeout()).isEqualTo(Duration.ofSeconds(900));
        assertThat(client(defaults.anthropic(), params -> null).maxOutputTokens()).isEqualTo(64000);
    }

    @ParameterizedTest(name = "{0} -> retryable={1}")
    @MethodSource("sdkErrors")
    void classifiesSdkErrors(AnthropicException error, boolean retryable) {
        LlmException mapped = AnthropicLlmClient.classify(error);

        assertThat(mapped.retryable()).isEqualTo(retryable);
        assertThat(mapped.getCause()).isSameAs(error);
    }

    static Stream<Arguments> sdkErrors() {
        Headers headers = Headers.builder().build();
        JsonValue body = JsonValue.from(Map.of());
        return Stream.of(
                Arguments.of(RateLimitException.builder().headers(headers).body(body).build(), true),
                Arguments.of(InternalServerException.builder().statusCode(500).headers(headers).body(body).build(),
                        true),
                Arguments.of(unexpected(529, headers, body), true),
                Arguments.of(unexpected(408, headers, body), true),
                Arguments.of(unexpected(409, headers, body), true),
                Arguments.of(BadRequestException.builder().headers(headers).body(body).build(), false),
                Arguments.of(UnauthorizedException.builder().headers(headers).body(body).build(), false),
                Arguments.of(PermissionDeniedException.builder().headers(headers).body(body).build(), false),
                Arguments.of(NotFoundException.builder().headers(headers).body(body).build(), false),
                Arguments.of(UnprocessableEntityException.builder().headers(headers).body(body).build(), false),
                Arguments.of(SseException.builder().statusCode(200).headers(headers)
                        .body(JsonValue.from(Map.of("type", "error", "error", Map.of("type", "overloaded_error"))))
                        .build(), true),
                Arguments.of(new AnthropicIoException("connection reset"), true),
                Arguments.of(new AnthropicInvalidDataException("bad data"), true),
                Arguments.of(new AnthropicException("unknown"), false));
    }

    private static UnexpectedStatusCodeException unexpected(int status, Headers headers, JsonValue body) {
        return UnexpectedStatusCodeException.builder().statusCode(status).headers(headers).body(body).build();
    }

    private static AnthropicLlmClient client(LlmProperties.Anthropic settings,
                                             AnthropicLlmClient.MessageSender sender) {
        return new AnthropicLlmClient(settings, CODEC, sender);
    }

    private static LlmProperties.Anthropic settings(boolean enabled, String key) {
        return new LlmProperties.Anthropic(enabled, "claude-opus-5-5", key, 16000, Duration.ofSeconds(180));
    }

    /** The server-sent events of a streamed response carrying {@link #TEST_PLAN_JSON} in two text deltas. */
    private static Stream<RawMessageStreamEvent> streamEvents(boolean complete) throws Exception {
        int half = TEST_PLAN_JSON.length() / 2;
        List<String> events = new ArrayList<>(List.of(
                "{\"type\": \"message_start\", \"message\": {\"id\": \"msg_stream\", \"type\": \"message\", "
                        + "\"role\": \"assistant\", \"content\": [], \"model\": \"claude-opus-5-5\", "
                        + "\"stop_reason\": null, \"stop_sequence\": null, \"usage\": {\"input_tokens\": 1200, "
                        + "\"output_tokens\": 1, \"cache_creation_input_tokens\": 0, "
                        + "\"cache_read_input_tokens\": 0}}}",
                "{\"type\": \"content_block_start\", \"index\": 0, \"content_block\": {\"type\": \"text\", "
                        + "\"text\": \"\"}}",
                "{\"type\": \"ping\"}",
                textDelta(TEST_PLAN_JSON.substring(0, half)),
                textDelta(TEST_PLAN_JSON.substring(half)),
                "{\"type\": \"content_block_stop\", \"index\": 0}",
                "{\"type\": \"message_delta\", \"delta\": {\"stop_reason\": \"end_turn\", "
                        + "\"stop_sequence\": null}, \"usage\": {\"output_tokens\": 340}}"));
        if (complete) {
            events.add("{\"type\": \"message_stop\"}");
        }
        List<RawMessageStreamEvent> parsed = new ArrayList<>();
        for (String event : events) {
            parsed.add(ObjectMappers.jsonMapper().readValue(event, RawMessageStreamEvent.class));
        }
        return parsed.stream();
    }

    private static Stream<RawMessageStreamEvent> events(boolean complete) {
        try {
            return streamEvents(complete);
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the test stream", e);
        }
    }

    private static String textDelta(String text) throws Exception {
        return "{\"type\": \"content_block_delta\", \"index\": 0, \"delta\": {\"type\": \"text_delta\", "
                + "\"text\": " + ObjectMappers.jsonMapper().writeValueAsString(text) + "}}";
    }

    private static Message message(String text, StopReason stopReason) {
        return Message.builder()
                .id("msg_test")
                .content(List.of(ContentBlock.ofText(TextBlock.builder().text(text).citations(List.of()).build())))
                .model("claude-opus-5-5")
                .stopReason(stopReason)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .container(Optional.empty())
                .diagnostics(Optional.empty())
                .usage(Usage.builder().inputTokens(1200L).outputTokens(340L).cacheCreationInputTokens(0L)
                        .cacheReadInputTokens(0L).cacheCreation(Optional.empty()).inferenceGeo(Optional.empty())
                        .serverToolUse(Optional.empty()).serviceTier(Optional.empty())
                        .outputTokensDetails(Optional.empty()).speed(Optional.empty()).build())
                .build();
    }
}
