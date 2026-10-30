package com.agenticsdlc.shortener.service;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Application event published for every successful redirect and consumed asynchronously by {@link ClickRecorder}.
 *
 * <p>The raw client IP only lives in memory for the duration of the event; {@link #toString()} redacts it so it
 * cannot leak into logs.
 *
 * @param linkId     id of the clicked link
 * @param occurredAt redirect instant
 * @param referrer   raw {@code Referer} header, if any
 * @param userAgent  raw {@code User-Agent} header, if any
 * @param clientIp   resolved client IP (never persisted)
 */
public record ClickRecorded(
        long linkId,
        Instant occurredAt,
        @Nullable String referrer,
        @Nullable String userAgent,
        String clientIp) {

    @Override
    public String toString() {
        return "ClickRecorded[linkId=" + linkId + ", occurredAt=" + occurredAt + ", clientIp=<redacted>]";
    }
}
