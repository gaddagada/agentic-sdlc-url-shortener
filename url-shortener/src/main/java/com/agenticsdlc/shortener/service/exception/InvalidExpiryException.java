package com.agenticsdlc.shortener.service.exception;

/**
 * The requested expiry is ambiguous, in the past, or beyond {@code shortener.max-ttl}. Mapped to
 * {@code 400 Bad Request}.
 */
public final class InvalidExpiryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message human readable reason, safe to return to the client
     */
    public InvalidExpiryException(String message) {
        super(message);
    }
}
