package com.agenticsdlc.orchestrator.core.metrics;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * Metrics recorder SPI (the app provides a Micrometer adapter). Called by the engine under the run lock;
 * implementations must be fast and must not call back into the engine. Exceptions are caught and logged.
 */
public interface MetricsRecorder {

    /**
     * An agent attempt finished and the stage moved to {@code status}.
     *
     * @param stageId stage id
     * @param status resulting stage status
     * @param durationMs attempt duration
     */
    void stageCompleted(String stageId, StageStatus status, long durationMs);

    /**
     * A retry was scheduled.
     *
     * @param stageId stage id
     */
    void retry(String stageId);

    /**
     * The deterministic fallback was engaged.
     *
     * @param stageId stage id
     */
    void fallback(String stageId);

    /**
     * A rollback was executed.
     *
     * @param runId run id
     */
    void rollback(String runId);

    /**
     * A run completed.
     *
     * @param durationMs end-to-end duration
     * @param activeMs active duration (end-to-end minus human wait)
     */
    void runCompleted(long durationMs, long activeMs);

    /**
     * An agent call finished.
     *
     * @param providerId provider id
     * @param role agent role
     * @param latencyMs latency
     * @param inputTokens LLM input tokens (0 for deterministic agents)
     * @param outputTokens LLM output tokens (0 for deterministic agents)
     */
    void agentCall(String providerId, AgentRole role, long latencyMs, long inputTokens, long outputTokens);

    /**
     * An agent call finished, with prompt cache usage. The engine calls this overload; the default delegates to
     * {@link #agentCall(String, AgentRole, long, long, long)} and drops the cache counts, so existing recorders keep
     * working unchanged.
     *
     * @param providerId provider id
     * @param role agent role
     * @param latencyMs latency
     * @param inputTokens LLM input tokens, cache reads and writes included (0 for deterministic agents)
     * @param outputTokens LLM output tokens (0 for deterministic agents)
     * @param cacheReadTokens input tokens served from the provider's prompt cache
     * @param cacheCreationTokens input tokens written to the provider's prompt cache
     */
    default void agentCall(String providerId, AgentRole role, long latencyMs, long inputTokens, long outputTokens,
                           long cacheReadTokens, long cacheCreationTokens) {
        agentCall(providerId, role, latencyMs, inputTokens, outputTokens);
    }

    /**
     * The estimated cost of an LLM call was computed (called for every attempt that reported LLM usage, right after
     * {@link #agentCall(String, AgentRole, long, long, long, long, long)}). The default does nothing.
     *
     * @param providerId provider id
     * @param role agent role
     * @param costMicroUsd cost in micro-dollars (millionths of a US dollar), or null when the engine's price book has
     *                     no price for the provider and model
     */
    default void llmCost(String providerId, AgentRole role, @Nullable Long costMicroUsd) {
        // optional
    }

    /**
     * A human decided on an approval.
     *
     * @param decisionMs time from request to decision
     * @param approved whether it was approved
     */
    void approvalDecision(long decisionMs, boolean approved);
}
