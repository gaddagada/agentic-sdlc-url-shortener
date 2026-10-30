package com.agenticsdlc.orchestrator.core.model;

/**
 * Why a run was safe-stopped.
 */
public enum HaltReason {
    /** A human stopped the run. */
    OPERATOR_STOP,
    /** A policy rule denied the requirement or an artifact; needs a re-plan. */
    POLICY_DENIED,
    /** A stage failed after retries, fallback or feedback loops were exhausted. */
    AGENT_FAILURE_EXHAUSTED,
    /** A run budget (agent calls, tokens, active time) was exceeded. */
    BUDGET_EXCEEDED,
    /** The engine restarted while the run was in progress. */
    INTERRUPTED_BY_RESTART,
    /** At least one compensation failed during rollback. */
    ROLLBACK_FAILED;
}
