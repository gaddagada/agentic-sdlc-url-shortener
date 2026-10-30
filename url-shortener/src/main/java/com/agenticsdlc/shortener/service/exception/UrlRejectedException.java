package com.agenticsdlc.shortener.service.exception;

/**
 * The target URL violates the URL policy (scheme, credentials, private or internal host). Mapped to
 * {@code 422 Unprocessable Content}.
 */
public final class UrlRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Creates the exception.
     *
     * @param reason human readable reason, safe to return to the client
     */
    public UrlRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
