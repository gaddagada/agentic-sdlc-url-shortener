package com.agenticsdlc.orchestrator.app.api;

/**
 * Thrown when a mutating request does not carry a valid {@code X-Operator-Id} header (mapped to 400).
 */
public final class OperatorIdentityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong
     */
    public OperatorIdentityException(String message) {
        super(message);
    }
}
