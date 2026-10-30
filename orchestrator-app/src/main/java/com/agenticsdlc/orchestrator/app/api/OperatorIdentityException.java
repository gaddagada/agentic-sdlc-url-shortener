package com.agenticsdlc.orchestrator.app.api;

/**
 * Thrown when a mutating request does not carry a valid operator identity: a missing, malformed or reserved
 * {@code X-Operator-Id} header, or a token identity claim that is not a valid operator id (mapped to 400).
 */
public class OperatorIdentityException extends RuntimeException {

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
