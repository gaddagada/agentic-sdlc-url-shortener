package com.agenticsdlc.shortener.domain;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Lifecycle status of a short link.
 *
 * <p>Only {@link #ACTIVE} and {@link #DISABLED} are ever stored. {@link #EXPIRED} is derived at read time from
 * {@code expiresAt}, so expiry needs no background job and is exact to the request.
 */
public enum LinkStatus {
    ACTIVE,
    DISABLED,
    EXPIRED;

    /**
     * Computes the status a client observes at {@code now}.
     *
     * @param stored    the persisted status ({@code ACTIVE} or {@code DISABLED})
     * @param expiresAt optional expiry instant
     * @param now       the evaluation instant
     * @return {@code DISABLED} if disabled, {@code EXPIRED} if {@code expiresAt < now}, otherwise {@code ACTIVE}
     */
    public static LinkStatus effective(LinkStatus stored, @Nullable Instant expiresAt, Instant now) {
        if (stored == DISABLED) {
            return DISABLED;
        }
        if (expiresAt != null && expiresAt.isBefore(now)) {
            return EXPIRED;
        }
        return ACTIVE;
    }
}
