package com.agenticsdlc.shortener.service.exception;

/**
 * No link exists for the requested code. Mapped to {@code 404 Not Found}.
 *
 * <p>Thrown on the redirect hot path for unknown codes, so the stack trace is not captured.
 */
public final class LinkNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception.
     *
     * @param code the unknown code
     */
    public LinkNotFoundException(String code) {
        super("No short link exists for code '" + code + "'.", null, false, false);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
