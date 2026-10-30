package com.agenticsdlc.shortener.service.exception;

/**
 * The client's rate-limit bucket does not hold enough tokens for the request. Mapped to
 * {@code 429 Too Many Requests} with {@code Retry-After}, {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}.
 */
public final class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int requestedTokens;
    private final int limit;
    private final long remaining;
    private final long retryAfterSeconds;

    /**
     * Creates the exception.
     *
     * @param requestedTokens   tokens the request needed
     * @param limit             bucket capacity
     * @param remaining         whole tokens left in the bucket
     * @param retryAfterSeconds seconds until enough tokens are available
     */
    public RateLimitExceededException(int requestedTokens, int limit, long remaining, long retryAfterSeconds) {
        super("This request needs " + requestedTokens + " rate-limit tokens but only " + remaining
                + " are available; retry after " + retryAfterSeconds + " seconds.");
        this.requestedTokens = requestedTokens;
        this.limit = limit;
        this.remaining = remaining;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRequestedTokens() {
        return requestedTokens;
    }

    public int getLimit() {
        return limit;
    }

    public long getRemaining() {
        return remaining;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
