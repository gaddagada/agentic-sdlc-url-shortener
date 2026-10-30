package com.agenticsdlc.orchestrator.core.agent;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Usage information reported by an LLM-backed agent attempt; recorded in provenance, counters and budgets.
 *
 * @param providerId provider id ({@code anthropic}, {@code ollama})
 * @param model model name
 * @param promptHash SHA-256 of system plus user prompt
 * @param inputTokens input tokens
 * @param outputTokens output tokens
 */
public record LlmTrace(String providerId, String model, String promptHash, long inputTokens, long outputTokens) {

    /**
     * Normalizes text.
     */
    public LlmTrace {
        providerId = Immutables.text(providerId);
        model = Immutables.text(model);
        promptHash = Immutables.text(promptHash);
    }
}
