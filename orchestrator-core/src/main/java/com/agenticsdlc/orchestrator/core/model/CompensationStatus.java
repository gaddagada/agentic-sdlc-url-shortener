package com.agenticsdlc.orchestrator.core.model;

/**
 * Status of a registered compensation.
 */
public enum CompensationStatus {
    /** Registered, not executed. */
    REGISTERED,
    /** Executed successfully during rollback or abort. */
    EXECUTED,
    /** Execution failed. */
    FAILED;
}
