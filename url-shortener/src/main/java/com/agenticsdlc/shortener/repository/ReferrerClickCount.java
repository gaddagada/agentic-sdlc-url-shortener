package com.agenticsdlc.shortener.repository;

import org.jspecify.annotations.Nullable;

/**
 * Query projection: number of clicks per referrer host.
 *
 * @param referrerHost referrer host, {@code null} for direct traffic
 * @param clicks       clicks from that referrer
 */
public record ReferrerClickCount(@Nullable String referrerHost, long clicks) {
}
