package com.agenticsdlc.orchestrator.core.model;

import org.jspecify.annotations.Nullable;

/**
 * Cumulative counters of a run. Immutable; the {@code plus*} methods return updated copies.
 *
 * <p>LLM cost: {@code llmCostMicroUsd} is the estimated cost of all LLM calls of the run in micro-dollars (millionths
 * of a US dollar), priced by the engine's price book when each call is recorded. It is null exactly when at least one
 * call could not be priced (no price configured for its provider and model); {@code llmUnpricedCalls} counts those
 * calls, so a console can say "n calls unpriced". The canonical constructor keeps that invariant: a null cost with no
 * unpriced call (counters persisted before costs existed) counts every LLM call as unpriced, and becomes 0 when there
 * was no LLM call; a cost together with unpriced calls becomes null.</p>
 *
 * <p>The cache and unpriced-call counters are boxed so that JSON written before they existed (persisted run
 * snapshots) still reads: an absent value becomes 0. They are never null after construction.</p>
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
 * @param llmInputTokens LLM input tokens (prompt cache reads and writes included)
 * @param llmOutputTokens LLM output tokens
 * @param llmCacheReadTokens LLM input tokens served from a provider's prompt cache
 * @param llmCacheCreationTokens LLM input tokens written to a provider's prompt cache
 * @param llmCostMicroUsd estimated LLM cost in micro-dollars, or null when at least one call could not be priced
 * @param llmUnpricedCalls LLM calls that could not be priced
 */
public record RunCounters(int retries, int fallbacks, int rollbacks, int replans, int feedbackLoops,
                          int approvalsRequested, int approvalsApproved, int approvalsRejected, int approvalsRevoked,
                          int agentCalls, int llmCalls, long llmInputTokens, long llmOutputTokens,
                          Long llmCacheReadTokens, Long llmCacheCreationTokens, @Nullable Long llmCostMicroUsd,
                          Integer llmUnpricedCalls) {

    /** All counters zero (the LLM cost is a known 0). */
    public static final RunCounters ZERO = new RunCounters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0);

    /**
     * Normalizes the boxed counters (absent becomes 0) and the cost fields (see the class documentation).
     */
    @SuppressWarnings("ConstantValue")
    public RunCounters {
        llmCacheReadTokens = llmCacheReadTokens == null ? 0L : llmCacheReadTokens;
        llmCacheCreationTokens = llmCacheCreationTokens == null ? 0L : llmCacheCreationTokens;
        if (llmUnpricedCalls == null || llmUnpricedCalls < 0) {
            llmUnpricedCalls = 0;
        }
        if (llmCostMicroUsd == null && llmUnpricedCalls == 0) {
            if (llmCalls <= 0) {
                llmCostMicroUsd = 0L;
            } else {
                llmUnpricedCalls = llmCalls;
            }
        } else if (llmCostMicroUsd != null && llmUnpricedCalls > 0) {
            llmCostMicroUsd = null;
        }
    }

    /**
     * Creates counters without prompt cache counts and costs (the canonical constructor before they existed): cache
     * counts 0, and every LLM call counts as unpriced (cost null), or a known cost of 0 without LLM calls.
     *
     * @param retries retries
     * @param fallbacks fallbacks
     * @param rollbacks rollbacks
     * @param replans re-plans
     * @param feedbackLoops feedback loops
     * @param approvalsRequested approvals requested
     * @param approvalsApproved approvals granted
     * @param approvalsRejected approvals rejected
     * @param approvalsRevoked approvals revoked
     * @param agentCalls agent calls
     * @param llmCalls LLM calls
     * @param llmInputTokens LLM input tokens
     * @param llmOutputTokens LLM output tokens
     */
    public RunCounters(int retries, int fallbacks, int rollbacks, int replans, int feedbackLoops,
                       int approvalsRequested, int approvalsApproved, int approvalsRejected, int approvalsRevoked,
                       int agentCalls, int llmCalls, long llmInputTokens, long llmOutputTokens) {
        this(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls, llmCalls, llmInputTokens, llmOutputTokens, 0L, 0L,
                null, 0);
    }

    /** Copy helper for the non-LLM counters. */
    private RunCounters with(int newRetries, int newFallbacks, int newRollbacks, int newReplans, int newFeedbackLoops,
                             int newRequested, int newApproved, int newRejected, int newRevoked, int newAgentCalls) {
        return new RunCounters(newRetries, newFallbacks, newRollbacks, newReplans, newFeedbackLoops, newRequested,
                newApproved, newRejected, newRevoked, newAgentCalls, llmCalls, llmInputTokens, llmOutputTokens,
                llmCacheReadTokens, llmCacheCreationTokens, llmCostMicroUsd, llmUnpricedCalls);
    }

    /**
     * Returns a copy with one more retry.
     *
     * @return the copy
     */
    public RunCounters plusRetry() {
        return with(retries + 1, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more fallback.
     *
     * @return the copy
     */
    public RunCounters plusFallback() {
        return with(retries, fallbacks + 1, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more rollback.
     *
     * @return the copy
     */
    public RunCounters plusRollback() {
        return with(retries, fallbacks, rollbacks + 1, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more re-plan.
     *
     * @return the copy
     */
    public RunCounters plusReplan() {
        return with(retries, fallbacks, rollbacks, replans + 1, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more feedback loop.
     *
     * @return the copy
     */
    public RunCounters plusFeedbackLoop() {
        return with(retries, fallbacks, rollbacks, replans, feedbackLoops + 1, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more approval requested.
     *
     * @return the copy
     */
    public RunCounters plusApprovalRequested() {
        return with(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested + 1, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more approval granted.
     *
     * @return the copy
     */
    public RunCounters plusApprovalApproved() {
        return with(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved + 1,
                approvalsRejected, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more approval rejected.
     *
     * @return the copy
     */
    public RunCounters plusApprovalRejected() {
        return with(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected + 1, approvalsRevoked, agentCalls);
    }

    /**
     * Returns a copy with one more approval revoked.
     *
     * @return the copy
     */
    public RunCounters plusApprovalRevoked() {
        return with(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked + 1, agentCalls);
    }

    /**
     * Returns a copy with one more agent call.
     *
     * @return the copy
     */
    public RunCounters plusAgentCall() {
        return with(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested, approvalsApproved,
                approvalsRejected, approvalsRevoked, agentCalls + 1);
    }

    /**
     * Returns a copy with one more LLM call and the given token usage added; the call is not priced (it counts as an
     * unpriced call and makes the run's cost unknown).
     *
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     * @return the copy
     */
    public RunCounters plusLlmUsage(long inputTokens, long outputTokens) {
        return plusLlmUsage(inputTokens, outputTokens, 0L, 0L, null);
    }

    /**
     * Returns a copy with one more LLM call and the given token usage added; the call is not priced (it counts as an
     * unpriced call and makes the run's cost unknown).
     *
     * @param inputTokens input tokens (cache reads and writes included)
     * @param outputTokens output tokens
     * @param cacheReadTokens input tokens served from the prompt cache
     * @param cacheCreationTokens input tokens written to the prompt cache
     * @return the copy
     */
    public RunCounters plusLlmUsage(long inputTokens, long outputTokens, long cacheReadTokens,
                                    long cacheCreationTokens) {
        return plusLlmUsage(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens, null);
    }

    /**
     * Returns a copy with one more LLM call, its token usage and its cost added. A null cost marks the call as
     * unpriced: the run's cost becomes (and stays) unknown and {@code llmUnpricedCalls} grows by one.
     *
     * @param inputTokens input tokens (cache reads and writes included)
     * @param outputTokens output tokens
     * @param cacheReadTokens input tokens served from the prompt cache
     * @param cacheCreationTokens input tokens written to the prompt cache
     * @param costMicroUsd cost of the call in micro-dollars, or null when it could not be priced
     * @return the copy
     */
    public RunCounters plusLlmUsage(long inputTokens, long outputTokens, long cacheReadTokens,
                                    long cacheCreationTokens, @Nullable Long costMicroUsd) {
        Long cost = costMicroUsd == null || llmCostMicroUsd == null ? null : llmCostMicroUsd + costMicroUsd;
        int unpriced = llmUnpricedCalls + (costMicroUsd == null ? 1 : 0);
        return new RunCounters(retries, fallbacks, rollbacks, replans, feedbackLoops, approvalsRequested,
                approvalsApproved, approvalsRejected, approvalsRevoked, agentCalls, llmCalls + 1,
                llmInputTokens + inputTokens, llmOutputTokens + outputTokens, llmCacheReadTokens + cacheReadTokens,
                llmCacheCreationTokens + cacheCreationTokens, cost, unpriced);
    }

    /**
     * Returns the total LLM tokens used.
     *
     * @return input plus output tokens
     */
    public long llmTokens() {
        return llmInputTokens + llmOutputTokens;
    }

    /**
     * Returns the estimated LLM cost in US dollars.
     *
     * @return {@code llmCostMicroUsd / 1_000_000}, or null when the cost is unknown
     */
    public @Nullable Double llmCostUsd() {
        return llmCostMicroUsd == null ? null : llmCostMicroUsd / 1_000_000.0;
    }
}
