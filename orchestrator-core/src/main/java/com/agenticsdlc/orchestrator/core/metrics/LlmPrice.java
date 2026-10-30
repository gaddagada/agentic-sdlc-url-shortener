package com.agenticsdlc.orchestrator.core.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;

/**
 * The price of one LLM model, in US dollars per million tokens. Prices are operator configuration: the core ships
 * none.
 *
 * <p>Because the prices are per million tokens, the cost of {@code n} tokens in micro-dollars (millionths of a dollar)
 * is exactly {@code n * pricePerMillion}: 1 200 input tokens at 3.00 USD per million cost 3 600 micro-dollars
 * (0.0036 USD).</p>
 *
 * @param inputPerMillionUsd price of uncached input tokens
 * @param outputPerMillionUsd price of output tokens
 * @param cacheReadPerMillionUsd price of input tokens served from the prompt cache
 * @param cacheWritePerMillionUsd price of input tokens written to the prompt cache
 */
public record LlmPrice(double inputPerMillionUsd, double outputPerMillionUsd, double cacheReadPerMillionUsd,
                       double cacheWritePerMillionUsd) {

    /**
     * Validates the prices.
     *
     * @throws IllegalArgumentException when a price is negative, NaN or infinite
     */
    public LlmPrice {
        requireValid(inputPerMillionUsd, "inputPerMillionUsd");
        requireValid(outputPerMillionUsd, "outputPerMillionUsd");
        requireValid(cacheReadPerMillionUsd, "cacheReadPerMillionUsd");
        requireValid(cacheWritePerMillionUsd, "cacheWritePerMillionUsd");
    }

    /**
     * Creates a price that bills prompt cache reads and writes like uncached input (for providers without prompt
     * caching or without separate cache prices).
     *
     * @param inputPerMillionUsd input price per million tokens
     * @param outputPerMillionUsd output price per million tokens
     * @return the price
     */
    public static LlmPrice flat(double inputPerMillionUsd, double outputPerMillionUsd) {
        return new LlmPrice(inputPerMillionUsd, outputPerMillionUsd, inputPerMillionUsd, inputPerMillionUsd);
    }

    private static void requireValid(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be a finite, non-negative number of US dollars per "
                    + "million tokens, got " + value);
        }
    }

    /**
     * Returns the cost of a call in micro-dollars: uncached input ({@link LlmUsage#uncachedInputTokens()}), output,
     * cache reads and cache writes, each multiplied by its price per million tokens, summed exactly and rounded half
     * up to a whole micro-dollar.
     *
     * @param usage the usage (cache counts are parts of {@code inputTokens})
     * @return the cost in micro-dollars
     * @throws ArithmeticException when the cost exceeds {@link Long#MAX_VALUE} micro-dollars (the engine then
     *                             records the call as unpriced)
     */
    public long costMicroUsd(LlmUsage usage) {
        BigDecimal total = cost(usage.uncachedInputTokens(), inputPerMillionUsd)
                .add(cost(usage.outputTokens(), outputPerMillionUsd))
                .add(cost(usage.cacheReadTokens(), cacheReadPerMillionUsd))
                .add(cost(usage.cacheCreationTokens(), cacheWritePerMillionUsd));
        return total.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal cost(long tokens, double pricePerMillion) {
        return BigDecimal.valueOf(Math.max(0L, tokens)).multiply(BigDecimal.valueOf(pricePerMillion));
    }
}
