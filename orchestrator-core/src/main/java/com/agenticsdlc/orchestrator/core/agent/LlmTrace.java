package com.agenticsdlc.orchestrator.core.agent;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Usage information reported by an LLM-backed agent attempt; recorded in provenance, counters and budgets.
 *
 * @param providerId provider id ({@code anthropic}, {@code ollama})
 * @param model model name
 * @param promptHash SHA-256 of the system prompt, the stable context and the user prompt
 * @param inputTokens all input tokens, prompt cache reads and writes included
 * @param outputTokens output tokens
 * @param cacheReadTokens input tokens served from the provider's prompt cache (part of {@code inputTokens})
 * @param cacheCreationTokens input tokens written to the provider's prompt cache (part of {@code inputTokens})
 */
public record LlmTrace(String providerId, String model, String promptHash, long inputTokens, long outputTokens,
                       long cacheReadTokens, long cacheCreationTokens) {

    /**
     * Normalizes text.
     */
    public LlmTrace {
        providerId = Immutables.text(providerId);
        model = Immutables.text(model);
        promptHash = Immutables.text(promptHash);
    }

    /**
     * Creates a trace without prompt cache information (cache counts 0).
     *
     * @param providerId provider id
     * @param model model name
     * @param promptHash prompt hash
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     */
    public LlmTrace(String providerId, String model, String promptHash, long inputTokens, long outputTokens) {
        this(providerId, model, promptHash, inputTokens, outputTokens, 0L, 0L);
    }
}
