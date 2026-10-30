package com.agenticsdlc.orchestrator.app.api.dto;

import com.agenticsdlc.orchestrator.core.metrics.ReliabilityMetrics;

/**
 * Reliability metrics over all runs known to the engine (mirrors {@link ReliabilityMetrics}).
 *
 * @param runs run outcome statistics
 * @param stages stage execution statistics
 * @param latency latency statistics
 * @param recovery failure recovery statistics
 * @param approvals human approval statistics
 * @param llm LLM usage statistics
 */
public record ReliabilityMetricsView(Runs runs, Stages stages, Latency latency, Recovery recovery,
                                     Approvals approvals, Llm llm) {

    /**
     * Maps the core metrics.
     *
     * @param m the metrics
     * @return the view
     */
    public static ReliabilityMetricsView from(ReliabilityMetrics m) {
        ReliabilityMetrics.RunStats r = m.runs();
        ReliabilityMetrics.StageStats s = m.stages();
        ReliabilityMetrics.LatencyStats l = m.latency();
        ReliabilityMetrics.RecoveryStats rc = m.recovery();
        ReliabilityMetrics.ApprovalStats a = m.approvals();
        ReliabilityMetrics.LlmStats llm = m.llm();
        return new ReliabilityMetricsView(
                new Runs(r.total(), r.completed(), r.aborted(), r.safeStopped(), r.inProgress(), r.waitingHuman(),
                        r.successRate()),
                new Stages(s.executions(), s.retries(), s.retryRate(), s.fallbacks(), s.rollbacks(),
                        s.rollbackFrequency(), s.feedbackLoops(), s.replans()),
                new Latency(l.endToEndMsAvg(), l.endToEndMsP50(), l.endToEndMsP95(), l.activeMsAvg(),
                        l.humanWaitMsAvg(), l.agentCallMsAvg(), l.agentCallMsP95()),
                new Recovery(rc.incidents(), rc.recovered(), rc.mttrMs(), rc.unrecovered()),
                new Approvals(a.requested(), a.approved(), a.rejected(), a.revoked(), a.meanDecisionMs()),
                new Llm(llm.calls(), llm.inputTokens(), llm.outputTokens()));
    }

    /**
     * Run outcomes.
     *
     * @param total runs
     * @param completed completed runs
     * @param aborted aborted runs
     * @param safeStopped safe-stopped runs
     * @param inProgress running runs
     * @param waitingHuman runs waiting for a human
     * @param successRate completed / (completed + aborted)
     */
    public record Runs(int total, int completed, int aborted, int safeStopped, int inProgress, int waitingHuman,
                       double successRate) {
    }

    /**
     * Stage executions.
     *
     * @param executions attempts
     * @param retries retries
     * @param retryRate retries / executions
     * @param fallbacks fallbacks
     * @param rollbacks rollbacks
     * @param rollbackFrequency rollbacks / runs
     * @param feedbackLoops feedback loops
     * @param replans re-plans
     */
    public record Stages(long executions, long retries, double retryRate, long fallbacks, long rollbacks,
                         double rollbackFrequency, long feedbackLoops, long replans) {
    }

    /**
     * Latencies.
     *
     * @param endToEndMsAvg mean end-to-end latency of completed runs
     * @param endToEndMsP50 median end-to-end latency
     * @param endToEndMsP95 95th percentile end-to-end latency
     * @param activeMsAvg mean active time
     * @param humanWaitMsAvg mean human wait
     * @param agentCallMsAvg mean agent call latency
     * @param agentCallMsP95 95th percentile agent call latency
     */
    public record Latency(double endToEndMsAvg, long endToEndMsP50, long endToEndMsP95, double activeMsAvg,
                          double humanWaitMsAvg, double agentCallMsAvg, long agentCallMsP95) {
    }

    /**
     * Failure recovery.
     *
     * @param incidents runs with a failure
     * @param recovered runs that recovered
     * @param mttrMs mean time to recovery
     * @param unrecovered runs not recovered
     */
    public record Recovery(int incidents, int recovered, double mttrMs, int unrecovered) {
    }

    /**
     * Approvals.
     *
     * @param requested requested
     * @param approved approved
     * @param rejected rejected
     * @param revoked revoked
     * @param meanDecisionMs mean decision time
     */
    public record Approvals(long requested, long approved, long rejected, long revoked, double meanDecisionMs) {
    }

    /**
     * LLM usage.
     *
     * @param calls LLM calls
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     */
    public record Llm(long calls, long inputTokens, long outputTokens) {
    }
}
