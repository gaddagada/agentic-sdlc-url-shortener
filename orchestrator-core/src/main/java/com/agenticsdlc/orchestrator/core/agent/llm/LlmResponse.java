package com.agenticsdlc.orchestrator.core.agent.llm;

/**
 * A structured completion response.
 *
 * @param output the parsed output
 * @param rawText the raw response text
 * @param usage token usage
 * @param latencyMs provider latency
 * @param model the model that answered
 * @param <T> output type
 */
public record LlmResponse<T>(T output, String rawText, LlmUsage usage, long latencyMs, String model) {
}
