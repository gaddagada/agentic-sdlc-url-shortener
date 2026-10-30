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
     * @param ipSalt secret mixed into visitor IP hashes; rotate it to unlink historic hashes
     */
    public record Analytics(@NotBlank String ipSalt) {
    }
}
