package com.agenticsdlc.orchestrator.core.metrics;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * A {@link MetricsRecorder} that records nothing.
 */
public final class NoopMetricsRecorder implements MetricsRecorder {

    /** Shared instance. */
    public static final NoopMetricsRecorder INSTANCE = new NoopMetricsRecorder();

    /**
     * Creates the recorder.
     */
    public NoopMetricsRecorder() {
        // empty
    }

    @Override
    public void stageCompleted(String stageId, StageStatus status, long durationMs) {
        // no-op
    }

    @Override
    public void retry(String stageId) {
        // no-op
    }

    @Override
    public void fallback(String stageId) {
        // no-op
    }

    @Override
    public void rollback(String runId) {
        // no-op
    }

    @Override
    public void runCompleted(long durationMs, long activeMs) {
        // no-op
    }

    @Override
    public void agentCall(String providerId, AgentRole role, long latencyMs, long inputTokens, long outputTokens) {
        // no-op
    }

    @Override
    public void approvalDecision(long decisionMs, boolean approved) {
        // no-op
    }
}
