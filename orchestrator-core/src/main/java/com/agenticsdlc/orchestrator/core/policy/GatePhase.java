package com.agenticsdlc.orchestrator.core.policy;

/**
 * When a policy is evaluated.
 */
public enum GatePhase {
    /** When a run starts or is re-planned (evaluates the requirement). */
    RUN_START,
    /** Before a stage starts. */
    ENTRY,
    /** After a stage produced its artifact, before it is accepted as final. */
    EXIT;
}
