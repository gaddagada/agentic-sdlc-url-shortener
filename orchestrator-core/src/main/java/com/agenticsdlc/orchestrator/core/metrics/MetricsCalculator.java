package com.agenticsdlc.orchestrator.core.metrics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.util.Clocks;

/**
 * Computes {@link ReliabilityMetrics} from runs.
 *
 * <p>Definitions: success rate = completed / (completed + aborted), 0 without terminal runs; retry rate =
 * retries / executions (agent attempts); rollback frequency = rollbacks / total runs; end-to-end latency =
 * {@code endedAt - startedAt} over completed runs; active = end-to-end minus the sum of human-wait
 * intervals; agent-call latency over the provenance of every agent-produced artifact; MTTR = mean of
 * {@code recoveredAt - firstFailureAt} over recovered runs; mean decision time over approved and rejected
 * approvals. Percentiles use the nearest-rank method.</p>
 */
public final class MetricsCalculator {

    /** Provider id of artifacts produced by the engine itself (not agent calls). */
    public static final String ENGINE_PROVIDER = "engine";

    private MetricsCalculator() {
    }

    /**
     * Computes the metrics.
     *
     * @param runs runs (snapshot copies)
     * @param clock clock used for open intervals
     * @return the metrics
     */
    public static ReliabilityMetrics compute(Collection<Run> runs, Clock clock) {
        Instant now = Clocks.now(clock);
        int completed = 0;
        int aborted = 0;
        int safeStopped = 0;
        int inProgress = 0;
        int waitingHuman = 0;
        long executions = 0;
        long retries = 0;
        long fallbacks = 0;
        long rollbacks = 0;
        long feedbackLoops = 0;
        long replans = 0;
        long approvalsRequested = 0;
        long approvalsApproved = 0;
        long approvalsRejected = 0;
        long approvalsRevoked = 0;
        long llmCalls = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        List<Long> endToEnd = new ArrayList<>();
        List<Long> active = new ArrayList<>();
        List<Long> humanWait = new ArrayList<>();
        List<Long> agentCalls = new ArrayList<>();
        List<Long> mttr = new ArrayList<>();
        List<Long> decisions = new ArrayList<>();
        int incidents = 0;
        int recovered = 0;

        for (Run run : runs) {
            switch (run.status()) {
                case COMPLETED -> completed++;
                case ABORTED -> aborted++;
                case SAFE_STOPPED -> safeStopped++;
                case RUNNING -> inProgress++;
                case WAITING_HUMAN -> waitingHuman++;
            }
            RunCounters c = run.counters();
            executions += c.agentCalls();
            retries += c.retries();
            fallbacks += c.fallbacks();
            rollbacks += c.rollbacks();
            feedbackLoops += c.feedbackLoops();
            replans += c.replans();
            approvalsRequested += c.approvalsRequested();
            approvalsApproved += c.approvalsApproved();
            approvalsRejected += c.approvalsRejected();
            approvalsRevoked += c.approvalsRevoked();
            llmCalls += c.llmCalls();
            inputTokens += c.llmInputTokens();
            outputTokens += c.llmOutputTokens();

            if (run.status() == RunStatus.COMPLETED && run.endToEndMs() != null) {
                long e2e = run.endToEndMs();
                long wait = run.humanWaitMs(run.endedAt() != null ? run.endedAt() : now);
                endToEnd.add(e2e);
                humanWait.add(wait);
                active.add(Math.max(0L, e2e - wait));
            }
            for (Artifact artifact : run.artifacts()) {
                if (!ENGINE_PROVIDER.equals(artifact.provenance().providerId())) {
                    agentCalls.add(artifact.provenance().latencyMs());
                }
            }
            if (run.firstFailureAt() != null) {
                incidents++;
            }
            if (run.recoveredAt() != null && run.mttrMs() != null) {
                recovered++;
                mttr.add(run.mttrMs());
            }
            for (Approval approval : run.approvals()) {
                if (approval.decidedAt() != null && approval.decidedBy() != null) {
                    decisions.add(Clocks.millisBetween(approval.requestedAt(), approval.decidedAt()));
                }
            }
        }
        int total = runs.size();
        int terminal = completed + aborted;
        return new ReliabilityMetrics(
                new ReliabilityMetrics.RunStats(total, completed, aborted, safeStopped, inProgress, waitingHuman,
                        terminal == 0 ? 0.0 : (double) completed / terminal),
                new ReliabilityMetrics.StageStats(executions, retries,
                        executions == 0 ? 0.0 : (double) retries / executions, fallbacks, rollbacks,
                        total == 0 ? 0.0 : (double) rollbacks / total, feedbackLoops, replans),
                new ReliabilityMetrics.LatencyStats(mean(endToEnd), percentile(endToEnd, 50), percentile(endToEnd, 95),
                        mean(active), mean(humanWait), mean(agentCalls), percentile(agentCalls, 95)),
                new ReliabilityMetrics.RecoveryStats(incidents, recovered, mean(mttr), incidents - recovered),
                new ReliabilityMetrics.ApprovalStats(approvalsRequested, approvalsApproved, approvalsRejected,
                        approvalsRevoked, mean(decisions)),
                new ReliabilityMetrics.LlmStats(llmCalls, inputTokens, outputTokens));
    }

    /**
     * Returns the arithmetic mean, 0 for an empty list.
     *
     * @param values values
     * @return the mean
     */
    static double mean(List<Long> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    /**
     * Returns the nearest-rank percentile: the value at rank {@code ceil(p/100 * n)} of the sorted values,
     * 0 for an empty list.
     *
     * @param values values
     * @param p percentile (0 exclusive to 100 inclusive)
     * @return the percentile value
     */
    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }
}
