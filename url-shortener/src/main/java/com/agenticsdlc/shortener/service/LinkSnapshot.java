package com.agenticsdlc.shortener.service;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.LinkStatus;

/**
 * Immutable cache entry for the redirect path. Caching this instead of the JPA entity avoids sharing detached,
 * mutable entities across threads. Expiry is evaluated at read time, so a cached entry never outlives its link.
 *
 * @param id           link id
 * @param code         short code
 * @param originalUrl  target URL
 * @param storedStatus persisted status
 * @param expiresAt    optional expiry instant
 */
public record LinkSnapshot(long id, String code, String originalUrl, LinkStatus storedStatus,
                           @Nullable Instant expiresAt) {

    /**
     * Creates a snapshot of a persisted link.
     *
     * @param link a persisted link
     * @return its snapshot
     */
    public static LinkSnapshot of(Link link) {
        Long id = Objects.requireNonNull(link.getId(), "link must be persisted");
        return new LinkSnapshot(id, link.getCode(), link.getOriginalUrl(), link.getStoredStatus(),
                link.getExpiresAt());
    }

    /**
     * Returns the effective status at {@code now}.
     *
     * @param now evaluation instant
     * @return effective status
     */
    public LinkStatus statusAt(Instant now) {
        return LinkStatus.effective(storedStatus, expiresAt, now);
    }
}
