package com.agenticsdlc.orchestrator.core.metrics;

import org.jspecify.annotations.Nullable;

/**
 * Reliability metrics over a set of runs (see {@link MetricsCalculator} for the exact definitions).
 *
 * @param runs run outcome statistics
 * @param stages stage execution statistics
 * @param latency latency statistics
 * @param recovery recovery (MTTR) statistics
 * @param approvals approval statistics
 * @param llm LLM usage statistics
 */
public record ReliabilityMetrics(RunStats runs, StageStats stages, LatencyStats latency, RecoveryStats recovery,
                                 ApprovalStats approvals, LlmStats llm) {

    /**
     * Run outcome statistics.
     *
     * @param total all runs
     * @param completed COMPLETED runs
     * @param aborted ABORTED runs
     * @param safeStopped SAFE_STOPPED runs
     * @param inProgress RUNNING runs
     * @param waitingHuman WAITING_HUMAN runs
     * @param successRate {@code completed / (completed + aborted)}, 0 when there is no terminal run
     */
    public record RunStats(int total, int completed, int aborted, int safeStopped, int inProgress, int waitingHuman,
                           double successRate) {
    }

    /**
     * Stage execution statistics.
     *
     * @param executions agent attempts
     * @param retries retries scheduled
     * @param retryRate {@code retries / executions}
     * @param fallbacks fallbacks engaged
     * @param rollbacks rollbacks
     * @param rollbackFrequency {@code rollbacks / total runs}
     * @param feedbackLoops feedback loops
     * @param replans re-plans
     */
    public record StageStats(long executions, long retries, double retryRate, long fallbacks, long rollbacks,
                             double rollbackFrequency, long feedbackLoops, long replans) {
    }

    /**
     * Latency statistics: run latencies over completed runs, agent-call latencies over the provenance of all
     * agent-produced artifacts. Percentiles use nearest rank.
     *
     * @param endToEndMsAvg mean of {@code endedAt - startedAt}
     * @param endToEndMsP50 median end-to-end latency
     * @param endToEndMsP95 95th percentile end-to-end latency
     * @param activeMsAvg mean of end-to-end minus human wait
     * @param humanWaitMsAvg mean human wait
     * @param agentCallMsAvg mean agent-call latency
     * @param agentCallMsP95 95th percentile agent-call latency
     */
    public record LatencyStats(double endToEndMsAvg, long endToEndMsP50, long endToEndMsP95, double activeMsAvg,
                               double humanWaitMsAvg, double agentCallMsAvg, long agentCallMsP95) {
    }

    /**
     * Recovery statistics.
     *
     * @param incidents runs with a failed attempt ({@code firstFailureAt} set)
     * @param recovered runs whose first failed stage later succeeded ({@code recoveredAt} set)
     * @param mttrMs mean of {@code recoveredAt - firstFailureAt} over recovered runs
     * @param unrecovered {@code incidents - recovered}
     */
    public record RecoveryStats(int incidents, int recovered, double mttrMs, int unrecovered) {
    }

    /**
     * Approval statistics.
     *
     * @param requested approvals requested
     * @param approved approvals granted
     * @param rejected approvals rejected
     * @param revoked approvals revoked
     * @param meanDecisionMs mean time from request to human decision (approved or rejected)
     */
    public record ApprovalStats(long requested, long approved, long rejected, long revoked, double meanDecisionMs) {
    }

    /**
     * LLM usage statistics.
     *
     * <p>{@code estimatedCostUsd} sums the costs of the runs whose LLM calls were all priced (a run with an unpriced
     * call has an unknown cost and contributes nothing), so it is a lower bound whenever {@code unpricedCalls > 0};
     * it is null when there were LLM calls but no run with LLM calls has a known cost (for example with the default,
     * empty price book), and 0 when there were no LLM calls.</p>
     *
     * @param calls LLM-backed agent attempts
     * @param inputTokens input tokens (prompt cache reads and writes included)
     * @param outputTokens output tokens
     * @param cacheReadTokens input tokens served from a provider's prompt cache
     * @param cacheCreationTokens input tokens written to a provider's prompt cache
     * @param estimatedCostUsd estimated cost in US dollars, or null when nothing could be priced
     * @param unpricedCalls LLM calls without a configured price
     */
    public record LlmStats(long calls, long inputTokens, long outputTokens, long cacheReadTokens,
                           long cacheCreationTokens, @Nullable Double estimatedCostUsd, int unpricedCalls) {

        /**
         * Creates statistics without prompt cache counts and costs (the canonical constructor before they existed):
         * cache counts 0 and every call unpriced (cost null), or a cost of 0 without calls.
         *
         * @param calls LLM-backed agent attempts
         * @param inputTokens input tokens
         * @param outputTokens output tokens
         */
        public LlmStats(long calls, long inputTokens, long outputTokens) {
            this(calls, inputTokens, outputTokens, 0L, 0L, calls == 0 ? 0.0 : null,
                    (int) Math.min(Integer.MAX_VALUE, calls));
        }
    }
}
