package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;
import com.agenticsdlc.orchestrator.core.metrics.LlmPrice;
import com.agenticsdlc.orchestrator.core.metrics.LlmPriceBook;

/**
 * {@code sdlc.llm.pricing.providers.<provider>.default} and {@code .models.<model-or-prefix>} bind to
 * {@link LlmProperties.Pricing} and map to the engine's {@link LlmPriceBook}: exact models, prefixes at a name
 * boundary, provider defaults, cache prices defaulting to the input price, model names with dots in brackets, and no
 * prices at all by default.
 */
class LlmPricingPropertiesTest {

    @Test
    void noPricesShipByDefault() {
        LlmProperties properties = bind(Map.of());

        assertThat(properties.pricing().providers()).isEmpty();
        assertThat(properties.pricing().toPriceBook()).isSameAs(LlmPriceBook.EMPTY);
    }

    @Test
    void modelPrefixAndProviderDefaultEntriesMapToThePriceBook() {
        Map<String, String> values = new HashMap<>();
        values.put("sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.input-per-million", "5");
        values.put("sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.output-per-million", "25");
        values.put("sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.cache-read-per-million", "0.5");
        values.put("sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.cache-write-per-million", "6.25");
        values.put("sdlc.llm.pricing.providers.anthropic.default.input-per-million", "3");
        values.put("sdlc.llm.pricing.providers.anthropic.default.output-per-million", "15");
        values.put("sdlc.llm.pricing.providers.ollama.models.[qwen2.5-coder:7b].input-per-million", "0");
        values.put("sdlc.llm.pricing.providers.ollama.models.[qwen2.5-coder:7b].output-per-million", "0");

        LlmPriceBook book = bind(values).pricing().toPriceBook();

        assertThat(book.lookup("anthropic", "claude-opus-5")).contains(new LlmPrice(5, 25, 0.5, 6.25));
        assertThat(book.lookup("anthropic", "claude-opus-5-5")).as("prefix at a name boundary")
                .contains(new LlmPrice(5, 25, 0.5, 6.25));
        assertThat(book.lookup("anthropic", "claude-sonnet-5")).as("provider default, cache prices = input")
                .contains(new LlmPrice(3, 15, 3, 3));
        assertThat(book.lookup("ollama", "qwen2.5-coder:7b")).contains(LlmPrice.flat(0, 0));
        assertThat(book.lookup("ollama", "llama3")).as("no ollama default").isEmpty();
        // 1 000 uncached input + 200 output + 4 000 cache reads + 800 cache writes of claude-opus-5-5
        assertThat(book.costMicroUsd("anthropic", "claude-opus-5-5", new LlmUsage(5_800, 200, 4_000, 800)))
                .contains(1_000L * 5 + 200L * 25 + 2_000L + 5_000L);
    }

    @Test
    void negativePricesAreRejected() {
        LlmProperties.Price price = new LlmProperties.Price(-1.0, 2.0, null, null);

        assertThatThrownBy(price::toLlmPrice).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputPerMillionUsd");
    }

    private static LlmProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("sdlc.llm", LlmProperties.class);
    }
}
