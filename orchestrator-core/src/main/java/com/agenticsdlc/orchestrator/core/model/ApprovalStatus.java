package com.agenticsdlc.orchestrator.core.model;

/**
 * Status of an approval request.
 */
public enum ApprovalStatus {
    /** Waiting for a human decision. */
    PENDING,
    /** Approved by a human. */
    APPROVED,
    /** Rejected by a human. */
    REJECTED,
    /** Invalidated because upstream work changed (re-plan, feedback loop) or the run was aborted. */
    REVOKED;
}
