package com.agenticsdlc.shortener.service.exception;

/**
 * No free short code was found within the retry budget. Mapped to {@code 503 Service Unavailable}.
 *
 * <p>With 62^7 possible codes this indicates a saturated keyspace or a broken random source, not bad luck.
 */
public final class CodeGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param attempts number of codes tried
     */
    public CodeGenerationException(int attempts) {
        super("Could not allocate a unique short code after " + attempts + " attempts.");
    }
}
