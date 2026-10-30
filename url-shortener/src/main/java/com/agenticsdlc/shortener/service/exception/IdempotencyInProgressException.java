package com.agenticsdlc.shortener.service.exception;

/**
 * A request with the same {@code Idempotency-Key} is still being processed. Mapped to {@code 409 Conflict}
 * with {@code Retry-After}: the seconds until the reservation of the request in flight may be taken over (the rest of
 * its takeover window), so that a client retrying after that time gets either the stored response or the reservation.
 */
public final class IdempotencyInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    /**
     * Creates the exception with a {@code Retry-After} of one second. The key itself is not echoed back.
     */
    public IdempotencyInProgressException() {
        this(1L);
    }

    /**
     * Creates the exception. The key itself is not echoed back.
     *
     * @param retryAfterSeconds seconds until the reservation in flight may be taken over (at least 1 is reported)
     */
    public IdempotencyInProgressException(long retryAfterSeconds) {
        super("A request with the same Idempotency-Key is still being processed.");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    /** {@return seconds until the reservation of the request in flight may be taken over, at least 1} */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
