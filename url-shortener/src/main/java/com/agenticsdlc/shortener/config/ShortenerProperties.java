package com.agenticsdlc.shortener.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated configuration under the {@code shortener.*} prefix. Invalid values fail application startup.
 *
 * @param baseUrl               public base URL used to build {@code shortUrl} (trailing slash removed)
 * @param codeLength            length of generated Base62 codes
 * @param maxTtl                maximum distance of {@code expiresAt} from creation time
 * @param blockPrivateTargets   reject target URLs pointing at private/loopback/link-local/internal hosts
 * @param trustForwardedHeaders honour {@code X-Forwarded-For} (only behind a trusted reverse proxy)
 * @param rateLimit             token-bucket settings for link creation
 * @param analytics             click analytics settings
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") @NotBlank String baseUrl,
        @DefaultValue("7") @Min(4) @Max(32) int codeLength,
        @DefaultValue("365d") @NotNull Duration maxTtl,
        @DefaultValue("true") boolean blockPrivateTargets,
        @DefaultValue("false") boolean trustForwardedHeaders,
        @DefaultValue @Valid @NotNull RateLimit rateLimit,
        @DefaultValue @Valid @NotNull Analytics analytics) {

    /** Normalizes the base URL and rejects a non-positive maximum TTL. */
    public ShortenerProperties {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (maxTtl != null && (maxTtl.isZero() || maxTtl.isNegative())) {
            throw new IllegalArgumentException("shortener.max-ttl must be positive");
        }
    }

    /**
     * Token-bucket rate limit applied per client IP to {@code POST /api/v1/links}.
     *
     * @param enabled         whether the limiter filter is registered
     * @param capacity        bucket size (burst)
     * @param refillPerMinute tokens added per minute (sustained rate)
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") @Min(1) int capacity,
            @DefaultValue("20") @Min(1) int refillPerMinute) {
    }

    /**
     * Click analytics settings.
     *
     * @param ipSalt    HMAC key of the daily visitor hash; must be a secret outside local development (startup fails
     *                  with the placeholder value under the {@code postgres} or {@code prod} profile)
     * @param retention how long click events are kept before the retention job purges them (default 90 days)
     * @param recorder  sizing of the asynchronous click-recording executor
     */
    public record Analytics(
            @NotBlank String ipSalt,
            @DefaultValue("90d") @NotNull Duration retention,
            @DefaultValue @Valid @NotNull Recorder recorder) {

        /** Rejects a non-positive retention window. */
        public Analytics {
            if (retention != null && (retention.isZero() || retention.isNegative())) {
                throw new IllegalArgumentException("shortener.analytics.retention must be positive");
            }
        }
    }

    /**
     * Bounded executor that persists clicks off the redirect path. Every running task holds one database connection,
     * so {@code threads} caps the connections click recording can take from the pool; clicks arriving while all
     * threads are busy and the queue is full are dropped and counted ({@code shortener.clicks.dropped}).
     *
     * @param threads       maximum number of recorder threads (default 8); the core pool is half of it, rounded up
     * @param queueCapacity clicks buffered while the core threads are busy (default 10 000)
     */
    public record Recorder(
            @DefaultValue("8") @Min(1) @Max(256) int threads,
            @DefaultValue("10000") @Min(1) int queueCapacity) {

        /** {@return the core pool size: half of {@link #threads()}, rounded up} */
        public int coreThreads() {
            return (threads + 1) / 2;
        }
    }
}
