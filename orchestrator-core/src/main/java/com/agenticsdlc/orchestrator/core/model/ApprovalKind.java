package com.agenticsdlc.orchestrator.core.model;

/**
 * The gate an approval belongs to.
 */
public enum ApprovalKind {
    /** Before the stage starts. */
    ENTRY,
    /** Before the stage's artifact is accepted as final. */
    EXIT;
}
