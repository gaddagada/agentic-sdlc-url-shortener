package com.agenticsdlc.orchestrator.core.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Run-level budgets bounding autonomy. Per-stage attempts come from the stage's retry policy.
 *
 * @param maxFeedbackLoops maximum verification feedback loops and rework rejections (default 2)
 * @param maxReplans maximum re-plans (default 3)
 * @param maxAgentCalls maximum agent attempts across all stages (default 40)
 * @param maxLlmTokens maximum LLM input plus output tokens (default 200 000)
 * @param maxActiveDuration maximum active (non-human-wait) duration (default 2 hours)
 */
public record RunBudget(int maxFeedbackLoops, int maxReplans, int maxAgentCalls, long maxLlmTokens,
                        Duration maxActiveDuration) {

    /** Default budget. */
    public static final RunBudget DEFAULT = new RunBudget(2, 3, 40, 200_000L, Duration.ofHours(2));

    /**
     * Validates the budget.
     */
    public RunBudget {
        Objects.requireNonNull(maxActiveDuration, "maxActiveDuration");
        if (maxFeedbackLoops < 0 || maxReplans < 0 || maxAgentCalls < 1 || maxLlmTokens < 0) {
            throw new IllegalArgumentException("budget values must be non-negative and maxAgentCalls at least 1");
        }
        if (maxActiveDuration.isNegative() || maxActiveDuration.isZero()) {
            throw new IllegalArgumentException("maxActiveDuration must be positive");
        }
    }

    /**
     * Returns the default budget.
     *
     * @return {@link #DEFAULT}
     */
    public static RunBudget defaults() {
        return DEFAULT;
    }

    /**
     * Returns a copy with a different agent call budget.
     *
     * @param value max agent calls
     * @return the copy
     */
    public RunBudget withMaxAgentCalls(int value) {
        return new RunBudget(maxFeedbackLoops, maxReplans, value, maxLlmTokens, maxActiveDuration);
    }

    /**
     * Returns a copy with a different feedback loop budget.
     *
     * @param value max feedback loops
     * @return the copy
     */
    public RunBudget withMaxFeedbackLoops(int value) {
        return new RunBudget(value, maxReplans, maxAgentCalls, maxLlmTokens, maxActiveDuration);
    }

    /**
     * Returns a copy with a different re-plan budget.
     *
     * @param value max re-plans
     * @return the copy
     */
    public RunBudget withMaxReplans(int value) {
        return new RunBudget(maxFeedbackLoops, value, maxAgentCalls, maxLlmTokens, maxActiveDuration);
    }

    /**
     * Returns a copy with a different token budget.
     *
     * @param value max LLM tokens
     * @return the copy
     */
    public RunBudget withMaxLlmTokens(long value) {
        return new RunBudget(maxFeedbackLoops, maxReplans, maxAgentCalls, value, maxActiveDuration);
    }

    /**
     * Returns a copy with a different active duration budget.
     *
     * @param value max active duration
     * @return the copy
     */
    public RunBudget withMaxActiveDuration(Duration value) {
        return new RunBudget(maxFeedbackLoops, maxReplans, maxAgentCalls, maxLlmTokens, value);
    }

    /**
     * Returns this budget extended by one default allowance of every limit. Used when a human explicitly resumes a
     * run that safe-stopped with {@code BUDGET_EXCEEDED}: the resume is the authorization to spend more.
     *
     * @return the extended budget
     */
    public RunBudget extended() {
        return new RunBudget(maxFeedbackLoops + DEFAULT.maxFeedbackLoops, maxReplans + DEFAULT.maxReplans,
                maxAgentCalls + DEFAULT.maxAgentCalls, maxLlmTokens + DEFAULT.maxLlmTokens,
                maxActiveDuration.plus(DEFAULT.maxActiveDuration));
    }
}
