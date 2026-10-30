package com.agenticsdlc.orchestrator.app.api;

/**
 * Thrown when the {@code X-Operator-Id} header names another operator than the identity of the verified bearer
 * token ({@code oidc} mode; mapped to 400 {@link ProblemTypes#OPERATOR_IDENTITY_MISMATCH}).
 */
public final class OperatorIdentityMismatchException extends OperatorIdentityException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what disagrees (never the header value itself)
     */
    public OperatorIdentityMismatchException(String message) {
        super(message);
    }
}
