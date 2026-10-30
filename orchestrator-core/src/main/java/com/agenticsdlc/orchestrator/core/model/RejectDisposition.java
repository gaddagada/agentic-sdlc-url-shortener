package com.agenticsdlc.orchestrator.core.model;

/**
 * What happens after a human rejects a gate.
 */
public enum RejectDisposition {
    /** The stage re-runs with the rejection reason as feedback (counts as a feedback loop). */
    REWORK,
    /** The run is aborted. */
    ABORT;
}
