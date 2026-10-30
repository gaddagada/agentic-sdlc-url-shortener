package com.agenticsdlc.orchestrator.core.audit;

/**
 * Type of an audit event. Every state transition and governance action emits one.
 */
public enum AuditEventType {
    /** A run was created. */
    RUN_CREATED,
    /** A stage's predecessors succeeded and its entry gate is evaluated. */
    STAGE_READY,
    /** An entry or exit gate was evaluated. */
    GATE_EVALUATED,
    /** A human approval was requested. */
    APPROVAL_REQUESTED,
    /** A human approved. */
    APPROVAL_GRANTED,
    /** A human rejected. */
    APPROVAL_REJECTED,
    /** An approval was revoked because upstream work changed. */
    APPROVAL_REVOKED,
    /** Clarification questions were asked. */
    CLARIFICATION_REQUESTED,
    /** A human answered the clarification questions. */
    CLARIFICATION_PROVIDED,
    /** An agent attempt started. */
    STAGE_STARTED,
    /** An agent produced an output (with rationale). */
    AGENT_DECISION,
    /** An artifact version was accepted. */
    ARTIFACT_ACCEPTED,
    /** A stage succeeded. */
    STAGE_SUCCEEDED,
    /** A stage failed. */
    STAGE_FAILED,
    /** A retry was scheduled after a retryable failure. */
    RETRY_SCHEDULED,
    /** The deterministic fallback agent took over. */
    FALLBACK_ENGAGED,
    /** Feedback was routed back to an upstream stage (verification failure or rework). */
    FEEDBACK_LOOP,
    /** A policy rule produced a finding. */
    POLICY_FINDING,
    /** A policy denied the requirement or an artifact. */
    POLICY_DENIED,
    /** A checkpoint was recorded before a stage activation. */
    CHECKPOINT_CREATED,
    /** A compensation was executed. */
    COMPENSATION_EXECUTED,
    /** A compensation failed. */
    COMPENSATION_FAILED,
    /** A rollback to a checkpoint completed. */
    ROLLBACK_COMPLETED,
    /** The run was re-planned with a changed requirement. */
    REPLAN,
    /** A stage was invalidated (STALE or reset) because upstream work changed. */
    STAGE_INVALIDATED,
    /** A run budget was exceeded. */
    BUDGET_EXCEEDED,
    /** The run was safe-stopped. */
    SAFE_STOP,
    /** A human resumed the run. */
    RUN_RESUMED,
    /** The run completed. */
    RUN_COMPLETED,
    /** The run was aborted. */
    RUN_ABORTED,
    /** The run was interrupted by an engine restart. */
    RUN_INTERRUPTED;
}
