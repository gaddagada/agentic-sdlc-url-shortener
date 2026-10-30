package com.agenticsdlc.shortener.api;

import java.net.URI;

/**
 * RFC 9457 problem {@code type} URIs returned by the API. They identify the error class; clients should branch on
 * the {@code type}, not on the human-readable {@code title} or {@code detail}.
 */
public final class ProblemTypes {

    /** Common prefix of all problem types. */
    public static final String BASE = "https://agenticsdlc.dev/problems/";

    public static final URI VALIDATION_ERROR = of("validation-error");
    public static final URI MALFORMED_REQUEST = of("malformed-request");
    public static final URI LINK_NOT_FOUND = of("link-not-found");
    public static final URI LINK_GONE = of("link-gone");
    public static final URI ALIAS_CONFLICT = of("alias-conflict");
    public static final URI ALIAS_REJECTED = of("alias-rejected");
    public static final URI URL_REJECTED = of("url-rejected");
    public static final URI INVALID_EXPIRY = of("invalid-expiry");
    public static final URI IDEMPOTENCY_KEY_REUSED = of("idempotency-key-reused");
    public static final URI IDEMPOTENCY_REQUEST_IN_PROGRESS = of("idempotency-request-in-progress");
    public static final URI CODE_GENERATION_FAILED = of("code-generation-failed");
    public static final URI RATE_LIMITED = of("rate-limited");
    public static final URI INTERNAL_ERROR = of("internal-error");

    private ProblemTypes() {
    }

    private static URI of(String slug) {
        return URI.create(BASE + slug);
    }
}
