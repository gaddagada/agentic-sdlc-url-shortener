package com.agenticsdlc.orchestrator.core.model;

/**
 * Cumulative counters of a run. Immutable; the {@code plus*} methods return updated copies.
 *
 * @param retries retries scheduled
 * @param fallbacks deterministic fallbacks engaged
 * @param rollbacks rollbacks executed
 * @param replans re-plans
 * @param feedbackLoops verification feedback loops and rework rejections
 * @param approvalsRequested approvals requested
 * @param approvalsApproved approvals granted
 * @param approvalsRejected approvals rejected
 * @param approvalsRevoked approvals revoked
 * @param agentCalls agent attempts started (stage executions)
 * @param llmCalls LLM-backed agent attempts that reported usage
 * @param llmInputTokens LLM input tokens
 * @param llmOutputTokens LLM output tokens
 */
public record RunCounters(int retries, int fallbacks, int rollbacks, int replans, int feedbackLoops,
                          int approvalsRequested, int approvalsApproved, int approvalsRejected, int approvalsRevoked,
                          int agentCalls, int llmCalls, long llmInputTokens, long llmOutputTokens) {

    /** All counters zero. */
    public static final RunCounters ZERO = new RunCounters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L);

    /**
     * Returns a copy with one more retry.
     *
     * @return the copy
     */
    public RunCounters plusRetry() {
        return new RunCounters(retries + 1, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more fallback.
     *
     * @return the copy
     */
    public RunCounters plusFallback() {
        return new RunCounters(retries, fallbacks + 1, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more rollback.
     *
     * @return the copy
     */
    public RunCounters plusRollback() {
        return new RunCounters(retries, fallbacks, rollbacks + 1, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more re-plan.
     *
     * @return the copy
     */
    public RunCounters plusReplan() {
        return new RunCounters(retries, fallbacks, rollbacks, replans + 1, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more feedback loop.
     *
     * @return the copy
     */
    public RunCounters plusFeedbackLoop() {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops + 1, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more approval requested.
     *
     * @return the copy
     */
    public RunCounters plusApprovalRequested() {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested + 1,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more approval granted.
     *
     * @return the copy
     */
    public RunCounters plusApprovalApproved() {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved + 1, approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more approval rejected.
     *
     * @return the copy
     */
    public RunCounters plusApprovalRejected() {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected + 1, approvalsRevoked, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more approval revoked.
     *
     * @return the copy
     */
    public RunCounters plusApprovalRevoked() {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked + 1, agentCalls, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more agent call.
     *
     * @return the copy
     */
    public RunCounters plusAgentCall() {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls + 1, llmCalls, llmInputTokens,
                llmOutputTokens);
    }

    /**
     * Returns a copy with one more LLM call and the given token usage added.
     *
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     * @return the copy
     */
    public RunCounters plusLlmUsage(long inputTokens, long outputTokens) {
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls + 1,
                llmInputTokens + inputTokens, llmOutputTokens + outputTokens);
    }

    /**
     * Returns the total LLM tokens used.
     *
     * @return input plus output tokens
     */
    public long llmTokens() {
        return llmInputTokens + llmOutputTokens;
    }
}
