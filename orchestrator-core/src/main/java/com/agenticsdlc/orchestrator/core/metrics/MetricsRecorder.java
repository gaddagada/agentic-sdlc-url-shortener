package com.agenticsdlc.orchestrator.core.metrics;

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
     * A human decided on an approval.
     *
     * @param decisionMs time from request to decision
     * @param approved whether it was approved
     */
    void approvalDecision(long decisionMs, boolean approved);
}
