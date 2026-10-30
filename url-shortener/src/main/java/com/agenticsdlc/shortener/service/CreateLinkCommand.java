package com.agenticsdlc.shortener.service;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Input of {@link LinkService#create}. Transport-independent; its JSON form is also the idempotency fingerprint
 * input, so component order and names are part of that contract.
 *
 * @param url         target URL
 * @param customAlias optional client-chosen code
 * @param ttlSeconds  optional lifetime in seconds (exclusive with {@code expiresAt})
 * @param expiresAt   optional absolute expiry (exclusive with {@code ttlSeconds})
 */
public record CreateLinkCommand(
        String url,
        @Nullable String customAlias,
        @Nullable Long ttlSeconds,
        @Nullable Instant expiresAt) {
}
