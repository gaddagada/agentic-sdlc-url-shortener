package com.agenticsdlc.orchestrator.app.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmException;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.SseException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;

import tools.jackson.databind.JsonNode;

/**
 * {@link ProviderClient} for Claude through the official Anthropic Java SDK (Messages API with JSON structured
 * outputs).
 *
 * <p>The output schema is the payload record's schema from {@code JsonSchemas}, adapted to the structured-output
 * subset by {@link StructuredOutputSchema} and sent as {@code output_config.format}; the text block is parsed with
 * the core {@link JsonCodec}. This manual-schema path is used instead of the SDK's class-derived schema because the
 * payload records carry no Jackson 2 annotations and contain {@code Map<String, String>} members, which structured
 * outputs do not accept as-is.</p>
 *
 * <p>Requests are sent with the streaming Messages API ({@code createStreaming}) and the text deltas are
 * accumulated into the final {@link Message} with the SDK's {@link MessageAccumulator}: long outputs (up to
 * {@code sdlc.llm.anthropic.max-output-tokens}, 64 000 by default) keep the connection busy instead of sitting idle
 * until a read timeout, and the stop reason and usage are read from the accumulated message exactly as from a
 * non-streaming response.</p>
 *
 * <p>Prompt caching: the prompt is sent most-stable first with two ephemeral cache breakpoints. The system prompt
 * is one text block with {@code cache_control} (identical for every call of a role); the user message carries the
 * run-stable context ({@link LlmRequest#stableContext()}: requirement, clarification answers, accepted
 * specification, codebase index) as a first text block with its own {@code cache_control}, followed by the
 * per-attempt user text without one. Without a stable context the user message is that single text block. A
 * prefix shorter than the model's minimum cacheable length is simply not cached. Cache reads and writes are logged
 * at DEBUG and reported in the {@link LlmUsage} handed to the engine ({@code cacheReadTokens},
 * {@code cacheCreationTokens}), which counts them in the run counters, prices them with the configured price book and
 * reports them to the metrics recorder. {@code inputTokens} counts all input tokens (uncached + cache writes + cache
 * reads), so token budgets mean the same with and without caching.</p>
 *
 * <p>Thinking, temperature and effort are left at the model defaults. The SDK's own retries are disabled
 * ({@code maxRetries(0)}): retries, backoff and the deterministic fallback are owned by the engine, and SDK-level
 * retries would multiply the per-attempt stage timeout. Error mapping: rate limits, 408/409/429/5xx, errors sent
 * inside the stream, I/O errors, invalid data and a stream that ends early are retryable, and so is an output
 * truncated at {@code max_tokens}; other 4xx, refusals and a missing key are not. The API key is never logged.</p>
 */
public final class AnthropicLlmClient implements ProviderClient, AutoCloseable {

    /** Provider id. */
    public static final String PROVIDER_ID = "anthropic";

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private static final String MAP_NOTE = "\n\nOutput encoding note: JSON objects with free-form keys (for example "
            + "coverageMatrix) must be returned as arrays of {\"key\": ..., \"value\": ...} entries.";

    /** Ephemeral cache breakpoint with the configured lifetime ({@code sdlc.llm.anthropic.cache-ttl}). */
    private final CacheControlEphemeral cacheBreakpoint;

    /**
     * Sends a Messages API request and returns the complete response message (the streaming SDK call in
     * production, a canned message in tests).
     */
    @FunctionalInterface
    interface MessageSender {

        /**
         * Sends the request.
         *
         * @param params request parameters
         * @return the complete (accumulated) response message
         * @throws AnthropicException on SDK errors
         */
        Message send(MessageCreateParams params);
    }

    private final LlmProperties.Anthropic settings;
    private final JsonCodec codec;
    private final @Nullable MessageSender overrideSender;
    private final Map<Class<?>, StructuredOutputSchema> schemas = new ConcurrentHashMap<>();
    private volatile @Nullable AnthropicClient sdkClient;

    /**
     * Creates the client; the SDK client is built lazily on the first request.
     *
     * @param settings Anthropic settings
     * @param codec JSON codec used to parse outputs
     */
    public AnthropicLlmClient(LlmProperties.Anthropic settings, JsonCodec codec) {
        this(settings, codec, null);
    }

    /**
     * Creates the client with a custom sender (tests).
     *
     * @param settings Anthropic settings
     * @param codec JSON codec
     * @param sender sender, or null for the SDK
     */
    AnthropicLlmClient(LlmProperties.Anthropic settings, JsonCodec codec, @Nullable MessageSender sender) {
        this.settings = settings;
        this.codec = codec;
        this.overrideSender = sender;
        this.cacheBreakpoint = CacheControlEphemeral.builder()
                .ttl(CacheControlEphemeral.Ttl.of(settings.cacheTtl()))
                .build();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String model() {
        return settings.model();
    }

    @Override
    public boolean isAvailable() {
        return settings.enabled() && settings.hasApiKey();
    }

    @Override
    public String detail() {
        if (!settings.enabled()) {
            return "disabled by configuration (sdlc.llm.anthropic.enabled=false)";
        }
        return settings.hasApiKey() ? "API key configured (not verified until the first call)"
                : "ANTHROPIC_API_KEY not set";
    }

    @Override
    public int maxOutputTokens() {
        return settings.maxOutputTokens();
    }

    @Override
    public Duration requestTimeout() {
        return settings.timeout();
    }

    @Override
    public <T> LlmResponse<T> complete(LlmRequest request, Class<T> outputType) throws LlmException {
        if (!isAvailable()) {
            throw new LlmException("anthropic is not available: " + detail(), false);
        }
        StructuredOutputSchema schema = schemas.computeIfAbsent(outputType, StructuredOutputSchema::forRecord);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(Math.min(request.maxOutputTokens(), settings.maxOutputTokens()))
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.system())
                        .cacheControl(cacheBreakpoint)
                        .build()))
                .addUserMessageOfContentBlockParams(userBlocks(request, schema.hasMaps(), cacheBreakpoint))
                .outputConfig(OutputConfig.builder().format(outputFormat(schema)).build())
                .build();
        long started = System.nanoTime();
        Message message;
        try {
            message = sender().send(params);
        } catch (AnthropicException e) {
            throw classify(e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        StopReason stopReason = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            throw new LlmException("provider refused", false);
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            throw new LlmException("output truncated at max_tokens (" + params.maxTokens() + ")", true);
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new LlmException("the response contained no text output (stop reason " + stopReason + ")", true);
        }
        JsonNode tree = schema.restore(codec.readTree(text));
        T output = codec.mapper().treeToValue(tree, outputType);
        // input_tokens excludes the cached prefix: the engine is given every input token the call processed, with the
        // cache reads and writes as parts of it
        LlmUsage usage = usage(message.usage());
        String answeringModel = message.model().asString();
        LOG.debug("anthropic call model={} latencyMs={} inputTokens={} uncachedInputTokens={} cacheReadTokens={} "
                        + "cacheCreationTokens={} outputTokens={}", answeringModel, latencyMs, usage.inputTokens(),
                usage.uncachedInputTokens(), usage.cacheReadTokens(), usage.cacheCreationTokens(),
                usage.outputTokens());
        return new LlmResponse<>(output, text, usage, latencyMs, answeringModel);
    }

    /**
     * Maps the usage the Messages API reports to the engine's usage: Anthropic's {@code input_tokens} counts only the
     * uncached input, so the cache reads and writes are added to it ({@code inputTokens} stays inclusive) and passed
     * on as the cache counts.
     *
     * @param reported the reported usage
     * @return the usage
     */
    static LlmUsage usage(Usage reported) {
        long cacheRead = Math.max(0L, reported.cacheReadInputTokens().orElse(0L));
        long cacheCreation = Math.max(0L, reported.cacheCreationInputTokens().orElse(0L));
        return new LlmUsage(reported.inputTokens() + cacheCreation + cacheRead, reported.outputTokens(), cacheRead,
                cacheCreation);
    }

    /**
     * Builds the content of the user message: the run-stable context as a cached block (when there is one), then
     * the per-attempt user text (with the map encoding note when the schema has maps) without a breakpoint.
     *
     * @param request the request
     * @param hasMaps whether the output schema encodes maps as entry arrays
     * @return one or two text blocks
     */
    static List<ContentBlockParam> userBlocks(LlmRequest request, boolean hasMaps,
                                              CacheControlEphemeral breakpoint) {
        List<ContentBlockParam> blocks = new ArrayList<>(2);
        if (request.hasStableContext()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                    .text(request.stableContext())
                    .cacheControl(breakpoint)
                    .build()));
        }
        String user = hasMaps ? request.user() + MAP_NOTE : request.user();
        if (!user.isBlank() || blocks.isEmpty()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(user).build()));
        }
        return blocks;
    }

    private static JsonOutputFormat outputFormat(StructuredOutputSchema schema) {
        JsonOutputFormat.Schema.Builder json = JsonOutputFormat.Schema.builder();
        schema.schema().forEach((key, value) -> json.putAdditionalProperty(key, JsonValue.from(value)));
        return JsonOutputFormat.builder().schema(json.build()).build();
    }

    /**
     * Maps an SDK error to an {@link LlmException} with the retryable flag the engine needs.
     *
     * @param error the SDK error
     * @return the mapped exception (message free of secrets)
     */
    static LlmException classify(AnthropicException error) {
        String type = error.getClass().getSimpleName();
        if (error instanceof RateLimitException) {
            return new LlmException("anthropic rate limited (HTTP 429)", true, error);
        }
        if (error instanceof SseException) {
            // an error event inside an accepted stream (overloaded, api_error): transient by nature
            return new LlmException("anthropic stream error (" + type + ")", true, error);
        }
        if (error instanceof InternalServerException e) {
            return new LlmException("anthropic server error (HTTP " + e.statusCode() + ")", true, error);
        }
        if (error instanceof AnthropicServiceException e) {
            int status = e.statusCode();
            boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
            return new LlmException("anthropic " + (retryable ? "transient error" : "rejected the request") + " (HTTP "
                    + status + ", " + type + ")", retryable, error);
        }
        if (error instanceof AnthropicIoException || error instanceof AnthropicRetryableException) {
            return new LlmException("anthropic I/O error (" + type + ")", true, error);
        }
        if (error instanceof AnthropicInvalidDataException) {
            return new LlmException("anthropic returned data that could not be read (" + type + ")", true, error);
        }
        return new LlmException("anthropic client error (" + type + ")", false, error);
    }

    private MessageSender sender() {
        if (overrideSender != null) {
            return overrideSender;
        }
        AnthropicClient client = sdkClient;
        if (client == null) {
            synchronized (this) {
                client = sdkClient;
                if (client == null) {
                    client = AnthropicOkHttpClient.builder()
                            .apiKey(settings.apiKey())
                            .timeout(settings.timeout())
                            .maxRetries(0)
                            .build();
                    sdkClient = client;
                }
            }
        }
        AnthropicClient ready = client;
        return params -> {
            try (StreamResponse<RawMessageStreamEvent> stream = ready.messages().createStreaming(params)) {
                return accumulate(stream.stream());
            }
        };
    }

    /**
     * Accumulates the events of a streamed Messages API response into the final message (text deltas, stop reason
     * and usage).
     *
     * @param events the stream events in arrival order
     * @return the complete message
     * @throws AnthropicInvalidDataException when the stream ends before {@code message_stop} (retryable)
     */
    static Message accumulate(Stream<RawMessageStreamEvent> events) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        events.forEach(accumulator::accumulate);
        try {
            return accumulator.message();
        } catch (IllegalStateException e) {
            throw new AnthropicInvalidDataException("the response stream ended before message_stop", e);
        }
    }

    @Override
    public void close() {
        AnthropicClient client = sdkClient;
        if (client != null) {
            client.close();
        }
    }
}
