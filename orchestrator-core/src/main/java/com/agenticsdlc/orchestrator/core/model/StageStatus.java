package com.agenticsdlc.orchestrator.core.model;

/**
 * Status of a stage execution (see the stage state machine in the engineering specification, section 5.4).
 */
public enum StageStatus {
    /** Not started in the current plan; waits for its predecessors and entry gate. */
    PENDING,
    /** An agent attempt is in flight. */
    RUNNING,
    /** The last attempt failed with a retryable failure; a retry is scheduled. */
    RETRY_SCHEDULED,
    /** Waiting for a human approval at the entry or exit gate. */
    AWAITING_APPROVAL,
    /** Waiting for a human to answer clarification questions. */
    AWAITING_CLARIFICATION,
    /** The stage's artifact is accepted and all gates passed. */
    SUCCEEDED,
    /** Attempts (and fallback) exhausted or the gate denied; triggers rollback. */
    FAILED,
    /** Previously succeeded, but an upstream artifact changed; will re-run. */
    STALE,
    /** Cancelled because the run was aborted. */
    CANCELLED;

    /**
     * Returns whether the stage waits for a human.
     *
     * @return true for AWAITING_APPROVAL and AWAITING_CLARIFICATION
     */
    public boolean isAwaitingHuman() {
        return this == AWAITING_APPROVAL || this == AWAITING_CLARIFICATION;
    }

    /**
     * Returns whether the engine is working on the stage.
     *
     * @return true for RUNNING and RETRY_SCHEDULED
     */
    public boolean isInProgress() {
        return this == RUNNING || this == RETRY_SCHEDULED;
    }

    /**
     * Returns whether the stage can be scheduled once its predecessors succeeded.
     *
     * @return true for PENDING and STALE
     */
    public boolean isSchedulable() {
        return this == PENDING || this == STALE;
    }
}
