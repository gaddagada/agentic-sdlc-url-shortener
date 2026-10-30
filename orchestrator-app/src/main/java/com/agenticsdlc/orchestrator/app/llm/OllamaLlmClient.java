package com.agenticsdlc.orchestrator.app.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.agenticsdlc.orchestrator.core.agent.llm.JsonSchemas;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmException;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

import tools.jackson.databind.JsonNode;

/**
 * {@link ProviderClient} for a local Ollama server ({@code POST /api/chat} with a JSON-schema {@code format}).
 *
 * <p>The request carries the payload record's schema ({@link JsonSchemas#forRecord(Class)}) as {@code format},
 * {@code stream=false} and deterministic options ({@code temperature=0}); {@code message.content} is parsed into the
 * record with the core {@link JsonCodec} (unparseable output surfaces as a Jackson exception, which the stage agent
 * maps to a retryable INVALID_OUTPUT failure). {@code prompt_eval_count}/{@code eval_count} are reported as usage.
 * Availability is {@code GET /api/tags} answering within 2 s, cached for 30 s. Connection errors and 5xx are
 * retryable; 4xx are not.</p>
 */
public final class OllamaLlmClient implements ProviderClient {

    /** Provider id. */
    public static final String PROVIDER_ID = "ollama";

    /** Bound of the availability probe. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** How long a probe result is reused. */
    static final Duration PROBE_TTL = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(OllamaLlmClient.class);
    private static final int ERROR_BODY_MAX = 300;

    private final RestClient http;
    private final LlmProperties.Ollama settings;
    private final JsonCodec codec;
    private final Clock clock;
    private volatile @Nullable Probe probe;

    /**
     * The result of an availability probe.
     *
     * @param available whether Ollama answered
     * @param detail human-readable detail
     * @param checkedAt probe time
     */
    private record Probe(boolean available, String detail, Instant checkedAt) {
    }

    /**
     * Creates the client.
     *
     * @param builder REST client builder (with the request factory and timeouts already configured)
     * @param settings Ollama settings
     * @param codec JSON codec
     * @param clock clock used for the probe cache
     */
    public OllamaLlmClient(RestClient.Builder builder, LlmProperties.Ollama settings, JsonCodec codec, Clock clock) {
        String base = settings.baseUrl().endsWith("/")
                ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1) : settings.baseUrl();
        this.http = builder.clone().baseUrl(base).build();
        this.settings = settings;
        this.codec = codec;
        this.clock = clock;
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
        return probe().available();
    }

    @Override
    public String detail() {
        return probe().detail();
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("messages", List.of(Map.of("role", "system", "content", request.system()),
                Map.of("role", "user", "content", request.userWithContext())));
        body.put("stream", false);
        body.put("format", JsonSchemas.forRecord(outputType));
        body.put("options", Map.of("temperature", 0, "num_ctx", settings.numCtx(),
                "num_predict", Math.min(request.maxOutputTokens(), settings.maxOutputTokens())));
        long started = System.nanoTime();
        String raw = post(codec.toJson(body));
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        JsonNode response = codec.readTree(raw == null ? "{}" : raw);
        String content = response.path("message").path("content").asString("");
        if ("length".equals(response.path("done_reason").asString(""))) {
            throw new LlmException("ollama output truncated at num_predict", true);
        }
        if (content.isBlank()) {
            throw new LlmException("ollama returned no message content", true);
        }
        T output = codec.fromJson(content, outputType);
        LlmUsage usage = new LlmUsage(response.path("prompt_eval_count").asLong(0L),
                response.path("eval_count").asLong(0L));
        LOG.debug("ollama call model={} latencyMs={} inputTokens={} outputTokens={}", settings.model(), latencyMs,
                usage.inputTokens(), usage.outputTokens());
        return new LlmResponse<>(output, content, usage, latencyMs, response.path("model").asString(settings.model()));
    }

    private @Nullable String post(String json) throws LlmException {
        try {
            return http.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON).body(json).retrieve()
                    .body(String.class);
        } catch (HttpServerErrorException e) {
            throw new LlmException("ollama server error (HTTP " + e.getStatusCode().value() + "): "
                    + snippet(e), true, e);
        } catch (HttpClientErrorException e) {
            throw new LlmException("ollama rejected the request (HTTP " + e.getStatusCode().value() + "): "
                    + snippet(e), false, e);
        } catch (ResourceAccessException e) {
            throw new LlmException("ollama unreachable at " + settings.baseUrl() + ": " + e.getMessage(), true, e);
        } catch (RestClientException e) {
            throw new LlmException("ollama call failed: " + e.getMessage(), true, e);
        }
    }

    private static String snippet(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body.length() <= ERROR_BODY_MAX ? body : body.substring(0, ERROR_BODY_MAX) + "…";
    }

    private Probe probe() {
        Probe current = probe;
        Instant now = clock.instant();
        if (current != null && current.checkedAt().plus(PROBE_TTL).isAfter(now)) {
            return current;
        }
        Probe fresh = check(now);
        probe = fresh;
        return fresh;
    }

    private Probe check(Instant now) {
        CompletableFuture<Integer> call = CompletableFuture.supplyAsync(
                () -> http.get().uri("/api/tags").retrieve().toBodilessEntity().getStatusCode().value(),
                command -> Thread.ofVirtual().name("ollama-probe").start(command));
        try {
            int status = call.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return status == 200 ? new Probe(true, "reachable at " + settings.baseUrl(), now)
                    : new Probe(false, "unexpected HTTP " + status + " from " + settings.baseUrl(), now);
        } catch (TimeoutException e) {
            call.cancel(true);
            return new Probe(false, "no answer from " + settings.baseUrl() + " within " + PROBE_TIMEOUT.toSeconds()
                    + " s", now);
        } catch (ExecutionException e) {
            return new Probe(false, "not reachable at " + settings.baseUrl(), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(false, "availability check interrupted", now);
        }
    }
}
