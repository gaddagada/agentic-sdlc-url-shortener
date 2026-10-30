package com.agenticsdlc.shortener.service.exception;

/**
 * A request with the same {@code Idempotency-Key} is still being processed. Mapped to {@code 409 Conflict}
 * with {@code Retry-After}.
 */
public final class IdempotencyInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception. The key itself is not echoed back.
     */
    public IdempotencyInProgressException() {
        super("A request with the same Idempotency-Key is still being processed.");
    }
}
