package com.agenticsdlc.shortener.api;

/**
 * HTTP header names of the API shared by controllers, the exception handler and the servlet filters.
 */
public final class ApiHeaders {

    /** Optional request header making creation safe to retry. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    /** Response header set to {@code true} when a stored response is replayed. */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    /** Capacity of the client's token bucket. */
    public static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    /** Whole tokens left in the client's bucket after the request. */
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    /** Management API key ({@code shortener.security.api-key}). */
    public static final String API_KEY = "X-Api-Key";

    private ApiHeaders() {
    }
}
