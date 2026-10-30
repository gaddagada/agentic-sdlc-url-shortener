package com.agenticsdlc.shortener.service;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.shortener.domain.LinkStatus;

/**
 * Read model of a link as exposed to clients. Also the JSON body stored for idempotent replays.
 *
 * @param code        short code
 * @param shortUrl    absolute short URL ({@code shortener.base-url + "/" + code})
 * @param originalUrl target URL
 * @param status      effective status at read time
 * @param createdAt   creation instant
 * @param expiresAt   optional expiry instant
 * @param clickCount  total recorded clicks
 */
public record LinkView(
        String code,
        String shortUrl,
        String originalUrl,
        LinkStatus status,
        Instant createdAt,
        @Nullable Instant expiresAt,
        long clickCount) {
}
