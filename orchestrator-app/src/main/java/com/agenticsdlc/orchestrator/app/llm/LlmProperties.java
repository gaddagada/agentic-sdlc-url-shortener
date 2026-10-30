package com.agenticsdlc.orchestrator.app.llm;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * LLM provider configuration under {@code sdlc.llm.*}.
 *
 * @param anthropic Anthropic (Claude) settings
 * @param ollama Ollama (local model) settings
 */
@Validated
@ConfigurationProperties(prefix = "sdlc.llm")
public record LlmProperties(
        @DefaultValue @Valid @NotNull Anthropic anthropic,
        @DefaultValue @Valid @NotNull Ollama ollama) {

    /**
     * Anthropic settings. {@link #toString()} never prints the API key.
     *
     * @param enabled whether the provider is registered
     * @param model model id
     * @param apiKey API key (usually {@code ${ANTHROPIC_API_KEY:}}); blank makes the provider unavailable
     * @param maxOutputTokens maximum output tokens per request (default 64000; responses are streamed, so a long
     *                        change set does not hit HTTP read timeouts)
     * @param timeout request timeout of one streamed call (default 900 s; stage timeouts are scaled by
     *                {@code sdlc.engine.llm-timeout-scale} to stay above it)
     */
    public record Anthropic(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("claude-opus-5-5") @NotBlank String model,
            @DefaultValue("") String apiKey,
            @DefaultValue("64000") @Min(256) @Max(128000) int maxOutputTokens,
            @DefaultValue("900s") @NotNull Duration timeout) {

        /**
         * Returns whether an API key is configured.
         *
         * @return true when the key is not blank
         */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Anthropic[enabled=" + enabled + ", model=" + model + ", apiKey=" + (hasApiKey() ? "***" : "")
                    + ", maxOutputTokens=" + maxOutputTokens + ", timeout=" + timeout + "]";
        }
    }

    /**
     * Ollama settings.
     *
     * @param enabled whether the provider is registered
     * @param baseUrl Ollama base URL
     * @param model model name
     * @param timeout request (read) timeout
     * @param numCtx context window passed as {@code options.num_ctx}
     * @param maxOutputTokens maximum output tokens ({@code options.num_predict})
     */
    public record Ollama(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("http://localhost:11434") @NotBlank String baseUrl,
            @DefaultValue("qwen2.5-coder:7b") @NotBlank String model,
            @DefaultValue("240s") @NotNull Duration timeout,
            @DefaultValue("16384") @Min(2048) int numCtx,
            @DefaultValue("8192") @Min(256) int maxOutputTokens) {
    }
}
