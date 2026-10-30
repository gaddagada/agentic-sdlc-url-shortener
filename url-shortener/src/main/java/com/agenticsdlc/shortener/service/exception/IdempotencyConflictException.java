package com.agenticsdlc.shortener.service.exception;

/**
 * An {@code Idempotency-Key} was reused with a different request payload. Mapped to
 * {@code 422 Unprocessable Content}.
 */
public final class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception. The key itself is not echoed back.
     */
    public IdempotencyConflictException() {
        super("The Idempotency-Key was already used with a different request payload.");
    }
}
