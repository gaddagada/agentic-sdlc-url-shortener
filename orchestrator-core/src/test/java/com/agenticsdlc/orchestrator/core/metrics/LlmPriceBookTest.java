package com.agenticsdlc.orchestrator.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;

class LlmPriceBookTest {

    private static final LlmPrice OPUS = new LlmPrice(15.0, 75.0, 1.5, 18.75);
    private static final LlmPrice OPUS_55 = new LlmPrice(5.0, 25.0, 0.5, 6.25);
    private static final LlmPrice ANTHROPIC_DEFAULT = new LlmPrice(3.0, 15.0, 0.3, 3.75);

    private final LlmPriceBook book = LlmPriceBook.builder()
            .price("anthropic", "claude-opus-5", OPUS)
            .price("Anthropic", "CLAUDE-OPUS-5-5", OPUS_55)
            .price("anthropic", "claude", ANTHROPIC_DEFAULT)
            .providerDefault("ollama", LlmPrice.flat(0.0, 0.0))
            .build();

    @Test
    void looksUpExactModelThenLongestPrefixThenProviderDefault() {
        assertThat(book.lookup("anthropic", "claude-opus-5-5")).contains(OPUS_55);
        assertThat(book.lookup("ANTHROPIC", " Claude-Opus-5-5 ")).as("case-insensitive").contains(OPUS_55);
        assertThat(book.lookup("anthropic", "claude-opus-5-5-20261001")).as("longest prefix").contains(OPUS_55);
        assertThat(book.lookup("anthropic", "claude-opus-5-1")).contains(OPUS);
        assertThat(book.lookup("anthropic", "claude-sonnet-5")).contains(ANTHROPIC_DEFAULT);
        assertThat(book.lookup("anthropic", "claude-opus-50")).as("prefixes end at a name boundary")
                .contains(ANTHROPIC_DEFAULT);
        assertThat(book.lookup("anthropic", "claudette")).isEmpty();
        assertThat(book.lookup("anthropic", null)).isEmpty();
        assertThat(book.lookup("ollama", "llama3.1:8b")).contains(LlmPrice.flat(0.0, 0.0));
        assertThat(book.lookup("ollama", null)).as("provider default needs no model").isPresent();
        assertThat(book.lookup("openai", "gpt-5")).isEmpty();
        assertThat(book.lookup("", "x")).isEmpty();
    }

    @Test
    void aPrefixEndingWithASeparatorMatchesTheModelsThatContinueIt() {
        LlmPriceBook separated = LlmPriceBook.builder().price("anthropic", "claude-opus-", OPUS)
                .price("anthropic", "claude-opus-5.", OPUS_55).build();
        assertThat(separated.lookup("anthropic", "claude-opus-4-1")).contains(OPUS);
        assertThat(separated.lookup("anthropic", "claude-opus-5.5")).as("longest prefix").contains(OPUS_55);
        assertThat(separated.lookup("anthropic", "claude-opusx")).isEmpty();
        assertThat(separated.lookup("anthropic", "claude-opus")).isEmpty();
    }

    @Test
    void computesCostInMicroDollarsWithCacheTokensAsPartsOfTheInput() {
        // uncached input 1000 * 5 + output 200 * 25 + cache read 3000 * 0.5 + cache write 1000 * 6.25
        LlmUsage usage = new LlmUsage(5_000, 200, 3_000, 1_000);
        assertThat(usage.uncachedInputTokens()).isEqualTo(1_000);
        assertThat(book.costMicroUsd("anthropic", "claude-opus-5-5", usage)).contains(5_000L + 5_000L + 1_500L
                + 6_250L);
        assertThat(book.costMicroUsd("anthropic", "claude-opus-5-5", 5_000, 200, 3_000, 1_000)).contains(17_750L);
        assertThat(new LlmPrice(3.0, 15.0, 0.3, 3.75).costMicroUsd(new LlmUsage(1_200, 340))).as("1200*3 + 340*15")
                .isEqualTo(3_600L + 5_100L);
        assertThat(book.costMicroUsd("openai", "gpt-5", usage)).isEqualTo(Optional.empty());
    }

    @Test
    void roundsHalfUpToWholeMicroDollars() {
        LlmPrice cheap = new LlmPrice(0.25, 0.1, 0.0, 0.0);
        assertThat(cheap.costMicroUsd(new LlmUsage(2, 0))).as("0.5 rounds up").isEqualTo(1L);
        assertThat(cheap.costMicroUsd(new LlmUsage(1, 0))).as("0.25 rounds down").isEqualTo(0L);
        assertThat(cheap.costMicroUsd(new LlmUsage(0, 15))).as("1.5 rounds up").isEqualTo(2L);
        assertThat(new LlmPrice(0.1, 0.2, 0.0, 0.0).costMicroUsd(new LlmUsage(3, 3))).as("exact decimal: 0.3 + 0.6")
                .isEqualTo(1L);
        assertThat(cheap.costMicroUsd(new LlmUsage(10, 0, 50, 0))).as("cache counts beyond the input clamp to 0")
                .isEqualTo(0L);
    }

    @Test
    void emptyBookPricesNothingAndBuilderValidates() {
        assertThat(LlmPriceBook.EMPTY.isEmpty()).isTrue();
        assertThat(LlmPriceBook.builder().build()).isSameAs(LlmPriceBook.EMPTY);
        assertThat(LlmPriceBook.EMPTY.costMicroUsd("anthropic", "claude-opus-5-5", new LlmUsage(1, 1))).isEmpty();
        assertThat(book.isEmpty()).isFalse();
        assertThat(book.entries()).extracting(LlmPriceBook.Entry::providerId, LlmPriceBook.Entry::model)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("anthropic", "claude"),
                        org.assertj.core.groups.Tuple.tuple("anthropic", "claude-opus-5"),
                        org.assertj.core.groups.Tuple.tuple("anthropic", "claude-opus-5-5"),
                        org.assertj.core.groups.Tuple.tuple("ollama", null));
        assertThat(LlmPriceBook.builder().price("a", "m", OPUS).build())
                .isEqualTo(LlmPriceBook.builder().price("A", "M", OPUS).build())
                .hasSameHashCodeAs(LlmPriceBook.builder().price("a", "m", OPUS).build());
        assertThat(book.toString()).contains("claude-opus-5-5");
        assertThatThrownBy(() -> LlmPriceBook.builder().price(" ", "m", OPUS)).isInstanceOf(
                IllegalArgumentException.class).hasMessageContaining("providerId");
        assertThatThrownBy(() -> LlmPriceBook.builder().price("p", "", OPUS)).hasMessageContaining("model");
        assertThatThrownBy(() -> new LlmPrice(-1.0, 0, 0, 0)).hasMessageContaining("inputPerMillionUsd");
        assertThatThrownBy(() -> new LlmPrice(0, Double.NaN, 0, 0)).hasMessageContaining("outputPerMillionUsd");
        assertThatThrownBy(() -> new LlmPrice(0, 0, Double.POSITIVE_INFINITY, 0))
                .hasMessageContaining("cacheReadPerMillionUsd");
        assertThat(LlmPrice.flat(2.0, 8.0)).isEqualTo(new LlmPrice(2.0, 8.0, 2.0, 2.0));
    }
}
