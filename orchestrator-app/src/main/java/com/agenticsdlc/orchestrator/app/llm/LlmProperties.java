package com.agenticsdlc.orchestrator.app.llm;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import com.agenticsdlc.orchestrator.core.metrics.LlmPrice;
import com.agenticsdlc.orchestrator.core.metrics.LlmPriceBook;

/**
 * LLM provider configuration under {@code sdlc.llm.*}.
 *
 * @param anthropic Anthropic (Claude) settings
 * @param ollama Ollama (local model) settings
 * @param pricing prices used to estimate the cost of LLM calls (none by default)
 */
@Validated
@ConfigurationProperties(prefix = "sdlc.llm")
public record LlmProperties(
        @DefaultValue @Valid @NotNull Anthropic anthropic,
        @DefaultValue @Valid @NotNull Ollama ollama,
        @DefaultValue @Valid @NotNull Pricing pricing) {

    /**
     * LLM prices in US dollars per million tokens, by provider ({@code sdlc.llm.pricing.providers.<provider>}): a
     * provider {@code default} price and {@code models.<model-or-prefix>} prices. The engine prices every LLM call when
     * it records the call's usage: an exact model entry wins, then the longest model prefix that ends at a name
     * boundary ({@code claude-opus-5} prices {@code claude-opus-5-5}), then the provider default; a call without a
     * price is counted as unpriced and makes its run's cost unknown.
     *
     * <p>No prices ship with the application: prices change and differ by contract, so they must be copied from the
     * provider's current price list. Model names containing dots need the bracket notation of Spring Boot map keys,
     * for example {@code models."[qwen2.5-coder:7b]"}.</p>
     *
     * @param providers prices by provider id ({@code anthropic}, {@code ollama})
     */
    public record Pricing(@DefaultValue @NotNull Map<String, @Valid ProviderPricing> providers) {

        /**
         * Builds the engine's price book.
         *
         * @return the price book ({@link LlmPriceBook#EMPTY} without prices)
         * @throws IllegalArgumentException for a blank provider id or model name
         */
        public LlmPriceBook toPriceBook() {
            LlmPriceBook.Builder book = LlmPriceBook.builder();
            providers.forEach((provider, pricing) -> {
                if (pricing == null) {
                    return;
                }
                if (pricing.defaultPrice() != null) {
                    book.providerDefault(provider, pricing.defaultPrice().toLlmPrice());
                }
                pricing.models().forEach((model, price) -> {
                    if (price != null) {
                        book.price(provider, model, price.toLlmPrice());
                    }
                });
            });
            return book.build();
        }
    }

    /**
     * Prices of one provider.
     *
     * @param defaultPrice price of every model without its own entry (property {@code default}), or null
     * @param models prices by model name or model-name prefix
     */
    public record ProviderPricing(@Name("default") @Valid @Nullable Price defaultPrice,
                                  @DefaultValue @NotNull Map<String, @Valid Price> models) {
    }

    /**
     * One price, in US dollars per million tokens. Cache prices default to the input price when unset (for providers
     * without prompt caching or without separate cache prices).
     *
     * @param inputPerMillion price of uncached input tokens
     * @param outputPerMillion price of output tokens
     * @param cacheReadPerMillion price of input tokens read from the prompt cache, or null for the input price
     * @param cacheWritePerMillion price of input tokens written to the prompt cache, or null for the input price
     */
    public record Price(@NotNull @DecimalMin("0.0") Double inputPerMillion,
                        @NotNull @DecimalMin("0.0") Double outputPerMillion,
                        @Nullable @DecimalMin("0.0") Double cacheReadPerMillion,
                        @Nullable @DecimalMin("0.0") Double cacheWritePerMillion) {

        /**
         * Converts the price to the core's price.
         *
         * @return the price
         */
        public LlmPrice toLlmPrice() {
            return new LlmPrice(inputPerMillion, outputPerMillion,
                    cacheReadPerMillion == null ? inputPerMillion : cacheReadPerMillion,
                    cacheWritePerMillion == null ? inputPerMillion : cacheWritePerMillion);
        }
    }

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
