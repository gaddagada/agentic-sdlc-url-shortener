package com.agenticsdlc.orchestrator.app.llm;

/**
 * Receives the prompt-cache token counts of an LLM call. The core's usage record carries only input and output
 * tokens, so a provider with prompt caching reports its cache reads and writes here; the metrics adapter records them
 * as the Micrometer counters {@code sdlc.llm.cache.read.tokens} and {@code sdlc.llm.cache.creation.tokens} (tags
 * {@code provider}, {@code model}). Implementations must be fast and must not throw.
 */
@FunctionalInterface
public interface PromptCacheListener {

    /** A listener that ignores every call. */
    PromptCacheListener NONE = (providerId, model, cacheReadTokens, cacheCreationTokens) -> {
    };

    /**
     * Records the cache usage of one call.
     *
     * @param providerId provider id, for example {@code anthropic}
     * @param model the model that answered
     * @param cacheReadTokens input tokens served from the prompt cache
     * @param cacheCreationTokens input tokens written to the prompt cache
     */
    void cacheUsage(String providerId, String model, long cacheReadTokens, long cacheCreationTokens);
}
