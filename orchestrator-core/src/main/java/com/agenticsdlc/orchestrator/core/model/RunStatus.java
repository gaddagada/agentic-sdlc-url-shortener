package com.agenticsdlc.orchestrator.core.model;

/**
 * Lifecycle status of a run.
 */
public enum RunStatus {
    /** The engine is executing or scheduling stages. */
    RUNNING,
    /** No stage is running or scheduled and at least one stage awaits a human (approval or clarification). */
    WAITING_HUMAN,
    /** Halted in a consistent state (see the halt reason); can be resumed or re-planned. */
    SAFE_STOPPED,
    /** Every stage succeeded (terminal). */
    COMPLETED,
    /** A human rejected with ABORT (terminal). */
    ABORTED;

    /**
     * Returns whether the run reached a terminal status.
     *
     * @return true for COMPLETED and ABORTED
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED;
    }

    /**
     * Returns whether the engine is driving the run (RUNNING or WAITING_HUMAN).
     *
     * @return true when active
     */
    public boolean isActive() {
        return this == RUNNING || this == WAITING_HUMAN;
    }
}
