package com.agenticsdlc.orchestrator.core.model;

/**
 * Kind of an injected fault (fault-injection scenarios and tests).
 */
public enum FaultKind {
    /** A retryable failure. */
    TRANSIENT,
    /** A non-retryable failure. */
    FATAL,
    /** A simulated attempt timeout (retryable). */
    TIMEOUT,
    /** A simulated invalid agent output (retryable). */
    INVALID_OUTPUT;
}
