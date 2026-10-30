package com.agenticsdlc.orchestrator.core.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Run-level budgets bounding autonomy. Per-stage attempts come from the stage's retry policy.
 *
 * <p>The default token budget (600 000) is sized for a complete LLM run of the ten-stage default workflow with an
 * Opus-class model: every stage prompt carries the requirement specification and the full JSON of its direct
 * predecessors' artifacts (summaries of the rest), the late stages see a full change set, and retries plus a
 * verification feedback loop re-send that context. A live brownfield run measured roughly 4k to 40k input tokens
 * per stage (12k output for the design) and exhausted the former 200 000 default after nine calls. The budget
 * remains a safety stop, not a cost target: a run that exceeds it safe-stops with {@code BUDGET_EXCEEDED} and a
 * human resume grants one more default allowance.</p>
 *
 * <p>The default feedback loop budget (3, formerly 2) allows three re-implementations after failed verifications:
 * live LLM runs of a brownfield change used both former loops on diff hunks and test gaps and stopped at the gate
 * before the reworked change set could be verified.</p>
 *
 * @param maxFeedbackLoops maximum verification feedback loops and rework rejections (default
 *                         {@value #DEFAULT_MAX_FEEDBACK_LOOPS})
 * @param maxReplans maximum re-plans (default 3)
 * @param maxAgentCalls maximum agent attempts across all stages (default 40)
 * @param maxLlmTokens maximum LLM input plus output tokens (default 600 000)
 * @param maxActiveDuration maximum active (non-human-wait) duration (default 2 hours)
 */
public record RunBudget(int maxFeedbackLoops, int maxReplans, int maxAgentCalls, long maxLlmTokens,
                        Duration maxActiveDuration) {

    /** Default token budget: ten stages with full direct-predecessor artifacts in context, retries included. */
    public static final long DEFAULT_MAX_LLM_TOKENS = 600_000L;

    /** Default verification feedback loop (and rework) budget. */
    public static final int DEFAULT_MAX_FEEDBACK_LOOPS = 3;

    /** Default budget. */
    public static final RunBudget DEFAULT = new RunBudget(DEFAULT_MAX_FEEDBACK_LOOPS, 3, 40, DEFAULT_MAX_LLM_TOKENS,
            Duration.ofHours(2));

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
