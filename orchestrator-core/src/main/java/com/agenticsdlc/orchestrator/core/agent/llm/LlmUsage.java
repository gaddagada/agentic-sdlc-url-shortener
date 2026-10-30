package com.agenticsdlc.orchestrator.core.agent.llm;

/**
 * Token usage of a completion.
 *
 * @param inputTokens input tokens
 * @param outputTokens output tokens
 */
public record LlmUsage(long inputTokens, long outputTokens) {

    /**
     * Returns zero usage.
     *
     * @return zero usage
     */
    public static LlmUsage none() {
        return new LlmUsage(0L, 0L);
    }
}
