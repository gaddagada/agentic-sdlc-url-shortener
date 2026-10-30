package com.agenticsdlc.orchestrator.core.agent.llm;

/**
 * Token usage of a completion.
 *
 * <p>{@code inputTokens} counts <em>every</em> input token the provider processed for the request, including the
 * tokens read from and written to a prompt cache; {@code cacheReadTokens} and {@code cacheCreationTokens} are the
 * parts of {@code inputTokens} that were served from the cache or written to it. Uncached input is therefore
 * {@link #uncachedInputTokens()} = {@code inputTokens - cacheReadTokens - cacheCreationTokens} (never negative).
 * Clients of providers that report cache tokens separately (Anthropic's {@code input_tokens} excludes them) add them
 * to {@code inputTokens}, so token budgets keep counting the full prompt size.</p>
 *
 * @param inputTokens all input tokens, cache reads and cache writes included
 * @param outputTokens output tokens
 * @param cacheReadTokens input tokens served from the provider's prompt cache (0 when unknown or not cached)
 * @param cacheCreationTokens input tokens written to the provider's prompt cache (0 when unknown or not cached)
 */
public record LlmUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {

    /**
     * Creates usage without prompt cache information (cache counts 0).
     *
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     */
    public LlmUsage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0L, 0L);
    }

    /**
     * Returns zero usage.
     *
     * @return zero usage
     */
    public static LlmUsage none() {
        return new LlmUsage(0L, 0L);
    }

    /**
     * Returns the input tokens that were neither read from nor written to the prompt cache.
     *
     * @return {@code max(0, inputTokens - cacheReadTokens - cacheCreationTokens)}
     */
    public long uncachedInputTokens() {
        return Math.max(0L, inputTokens - Math.max(0L, cacheReadTokens) - Math.max(0L, cacheCreationTokens));
    }
}
