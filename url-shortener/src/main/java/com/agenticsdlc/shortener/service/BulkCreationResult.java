package com.agenticsdlc.shortener.service;

import org.jspecify.annotations.Nullable;

/**
 * Result of {@link BulkLinkService#createAll}.
 *
 * @param creation  per-item outcomes (fresh or replayed)
 * @param replayed  {@code true} when the stored response of an earlier request with the same
 *                  {@code Idempotency-Key} was returned
 * @param rateLimit the client's bucket after this request was charged; {@code null} when nothing was charged
 *                  (replay, or rate limiting disabled)
 */
public record BulkCreationResult(BulkCreation creation, boolean replayed, @Nullable RateLimitStatus rateLimit) {

    /**
     * Rate-limit state reported in the {@code X-RateLimit-*} response headers.
     *
     * @param limit     bucket capacity
     * @param remaining whole tokens left after the charge
     */
    public record RateLimitStatus(int limit, long remaining) {
    }
}
