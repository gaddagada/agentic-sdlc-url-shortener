package com.agenticsdlc.orchestrator.core.agent;

/**
 * Classification of a failed agent attempt.
 */
public enum FailureKind {
    /** Retryable (network, rate limit, overload). */
    TRANSIENT,
    /** Not retryable (refusal, programming error, bad configuration). */
    FATAL,
    /** The attempt exceeded the stage timeout (retryable). */
    TIMEOUT,
    /** The output failed parsing or validation (retryable; the error is fed back). */
    INVALID_OUTPUT;

    /**
     * Returns whether failures of this kind may be retried.
     *
     * @return false only for FATAL
     */
    public boolean retryable() {
        return this != FATAL;
    }
}
