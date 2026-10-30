package com.agenticsdlc.orchestrator.core.agent.llm;

/**
 * A provider error. Retryable errors (rate limits, overload, timeouts, 5xx, truncated output) become TRANSIENT
 * stage failures; others (refusals, 4xx, bad configuration) become FATAL, which engages the deterministic
 * fallback.
 */
public class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    /**
     * Creates the exception.
     *
     * @param message message (must not contain secrets)
     * @param retryable whether the call may be retried
     */
    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message message (must not contain secrets)
     * @param retryable whether the call may be retried
     * @param cause the cause
     */
    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns whether the call may be retried.
     *
     * @return true when retryable
     */
    public boolean retryable() {
        return retryable;
    }
}
