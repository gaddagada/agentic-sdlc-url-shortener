package com.agenticsdlc.shortener.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated configuration under the {@code shortener.*} prefix. Invalid values fail application startup.
 *
 * @param baseUrl               public base URL used to build {@code shortUrl} (trailing slash removed)
 * @param codeLength            length of generated Base62 codes
 * @param maxTtl                maximum distance of {@code expiresAt} from creation time
 * @param blockPrivateTargets   reject target URLs pointing at private/loopback/link-local/internal hosts
 * @param resolveTargets        additionally resolve target host names (DNS) at creation time and reject names that
 *                              resolve to a non-public address; only effective with {@code blockPrivateTargets}
 * @param resolveTimeout        upper bound of one host name resolution when {@code resolveTargets} is on
 * @param trustForwardedHeaders honour {@code X-Forwarded-For} (only behind a trusted reverse proxy)
 * @param rateLimit             token-bucket settings for link creation
 * @param analytics             click analytics settings
 * @param security              management API authentication
 * @param idempotency           {@code Idempotency-Key} handling (protection of requests still in flight)
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") @NotBlank String baseUrl,
        @DefaultValue("7") @Min(4) @Max(32) int codeLength,
        @DefaultValue("365d") @NotNull Duration maxTtl,
        @DefaultValue("true") boolean blockPrivateTargets,
        @DefaultValue("false") boolean resolveTargets,
        @DefaultValue("2s") @NotNull Duration resolveTimeout,
        @DefaultValue("false") boolean trustForwardedHeaders,
        @DefaultValue @Valid @NotNull RateLimit rateLimit,
        @DefaultValue @Valid @NotNull Analytics analytics,
        @DefaultValue @Valid @NotNull Security security,
        @DefaultValue @Valid @NotNull Idempotency idempotency) {

    /** Normalizes the base URL and rejects a non-positive maximum TTL or resolve timeout. */
    @ConstructorBinding
    public ShortenerProperties {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (maxTtl != null && (maxTtl.isZero() || maxTtl.isNegative())) {
            throw new IllegalArgumentException("shortener.max-ttl must be positive");
        }
        if (resolveTimeout != null && (resolveTimeout.isZero() || resolveTimeout.isNegative())) {
            throw new IllegalArgumentException("shortener.resolve-timeout must be positive");
        }
    }

    /**
     * Creates the properties with the default {@link Idempotency} settings (the constructor before they existed).
     *
     * @param baseUrl               public base URL
     * @param codeLength            length of generated codes
     * @param maxTtl                maximum TTL
     * @param blockPrivateTargets   reject non-public targets
     * @param resolveTargets        resolve target host names at creation time
     * @param resolveTimeout        bound of one host name resolution
     * @param trustForwardedHeaders honour {@code X-Forwarded-For}
     * @param rateLimit             token-bucket settings
     * @param analytics             click analytics settings
     * @param security              management API authentication
     */
    public ShortenerProperties(String baseUrl, int codeLength, Duration maxTtl, boolean blockPrivateTargets,
                               boolean resolveTargets, Duration resolveTimeout, boolean trustForwardedHeaders,
                               RateLimit rateLimit, Analytics analytics, Security security) {
        this(baseUrl, codeLength, maxTtl, blockPrivateTargets, resolveTargets, resolveTimeout, trustForwardedHeaders,
                rateLimit, analytics, security, Idempotency.defaults());
    }

    /**
     * Token-bucket rate limit applied per client to link creation: one token per created link
     * ({@code POST /api/v1/links} costs 1, {@code POST /api/v1/links/bulk} costs the number of items).
     *
     * <p>The client is the client IP: an IPv4 address as is, an IPv6 address by its first {@code ipv6PrefixLength}
     * bits. An IPv6 subscriber usually controls a whole /64 and can use any address in it, so keying by the full
     * address would hand it a fresh bucket per address. Both backends use the same key.</p>
     *
     * @param enabled          whether link creation is rate limited at all
     * @param capacity         bucket size (burst); also the largest bulk request a client can send
     * @param refillPerMinute  tokens added per minute (sustained rate)
     * @param backend          where buckets live: {@code memory} (per node, default) or {@code redis} (shared)
     * @param redisKeyPrefix   prefix of the bucket keys in Redis ({@code backend=redis})
     * @param ipv6PrefixLength leading bits of an IPv6 client address that identify the client (default 64, the usual
     *                         network of one subscriber; 32..128, where 128 keys every address separately)
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") @Min(1) @Max(1_000_000) int capacity,
            @DefaultValue("20") @Min(1) @Max(1_000_000) int refillPerMinute,
            @DefaultValue("memory") @NotNull Backend backend,
            @DefaultValue(RedisRateLimiter.DEFAULT_KEY_PREFIX) @NotBlank String redisKeyPrefix,
            @DefaultValue("64") @Min(32) @Max(128) int ipv6PrefixLength) {

        /** Default {@link #ipv6PrefixLength()}. */
        public static final int DEFAULT_IPV6_PREFIX_LENGTH = 64;
        /** Smallest allowed {@link #ipv6PrefixLength()}. */
        public static final int MIN_IPV6_PREFIX_LENGTH = 32;
        /** Largest allowed {@link #ipv6PrefixLength()} (every address is its own client). */
        public static final int MAX_IPV6_PREFIX_LENGTH = 128;

        /** Rejects an IPv6 prefix length outside 32..128 (also when the settings are built in code). */
        @ConstructorBinding
        public RateLimit {
            if (ipv6PrefixLength < MIN_IPV6_PREFIX_LENGTH || ipv6PrefixLength > MAX_IPV6_PREFIX_LENGTH) {
                throw new IllegalArgumentException("shortener.rate-limit.ipv6-prefix-length must be within "
                        + MIN_IPV6_PREFIX_LENGTH + ".." + MAX_IPV6_PREFIX_LENGTH + ", got " + ipv6PrefixLength);
            }
        }

        /**
         * Creates the settings with the default IPv6 prefix length (the constructor before it existed).
         *
         * @param enabled         whether link creation is rate limited
         * @param capacity        bucket size
         * @param refillPerMinute tokens added per minute
         * @param backend         bucket storage
         * @param redisKeyPrefix  Redis key prefix
         */
        public RateLimit(boolean enabled, int capacity, int refillPerMinute, Backend backend, String redisKeyPrefix) {
            this(enabled, capacity, refillPerMinute, backend, redisKeyPrefix, DEFAULT_IPV6_PREFIX_LENGTH);
        }

        /** Storage of the token buckets. */
        public enum Backend {
            /** In-memory buckets of each node ({@link TokenBucketRateLimiter}). */
            MEMORY,
            /** Buckets shared by all nodes in Redis ({@link RedisRateLimiter}). */
            REDIS
        }
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

        /** Never prints the HMAC key ({@code ipSalt}). */
        @Override
        public String toString() {
            return "Analytics[ipSalt=<redacted>, retention=" + retention + ", recorder=" + recorder + "]";
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

    /**
     * {@code Idempotency-Key} handling. A request with a key reserves the key while it runs. A retry with the same key
     * gets {@code 409} ({@code idempotency-request-in-progress}) while the reservation is protected, and takes the
     * reservation over once its takeover window has passed (the first request is then presumed crashed). The window
     * of a request is {@code pendingTakeoverAfter}, plus {@code pendingTakeoverPerItem} per item of a bulk request,
     * at most 10 minutes. It is stored with the reservation, so a retry is always judged by the window of the request
     * it would take over.
     *
     * @param pendingTakeoverAfter   protection of a request still in flight (default 60 s; positive, at most 10 min)
     * @param pendingTakeoverPerItem additional protection per item of a bulk request (default 1 s; not negative)
     */
    public record Idempotency(
            @DefaultValue("60s") @NotNull Duration pendingTakeoverAfter,
            @DefaultValue("1s") @NotNull Duration pendingTakeoverPerItem) {

        /** Upper bound of a takeover window. */
        public static final Duration MAX_PENDING_TAKEOVER = Duration.ofMinutes(10);

        /** Rejects a non-positive or too long base window and a negative per-item window. */
        @ConstructorBinding
        public Idempotency {
            if (pendingTakeoverAfter != null && (pendingTakeoverAfter.isZero() || pendingTakeoverAfter.isNegative()
                    || pendingTakeoverAfter.compareTo(MAX_PENDING_TAKEOVER) > 0)) {
                throw new IllegalArgumentException("shortener.idempotency.pending-takeover-after must be positive "
                        + "and at most " + MAX_PENDING_TAKEOVER);
            }
            if (pendingTakeoverPerItem != null && pendingTakeoverPerItem.isNegative()) {
                throw new IllegalArgumentException("shortener.idempotency.pending-takeover-per-item must not be "
                        + "negative");
            }
        }

        /** {@return the defaults: 60 s, plus 1 s per bulk item} */
        public static Idempotency defaults() {
            return new Idempotency(Duration.ofSeconds(60), Duration.ofSeconds(1));
        }

        /**
         * Returns the takeover window of a request.
         *
         * @param items items of a bulk request, or 0 for a single request
         * @return {@code pendingTakeoverAfter + items * pendingTakeoverPerItem}, at most {@link #MAX_PENDING_TAKEOVER}
         */
        public Duration takeoverWindow(int items) {
            Duration window = pendingTakeoverAfter.plus(pendingTakeoverPerItem.multipliedBy(Math.max(0, items)));
            return window.compareTo(MAX_PENDING_TAKEOVER) > 0 ? MAX_PENDING_TAKEOVER : window;
        }
    }

    /**
     * Management API authentication.
     *
     * @param apiKey shared secret expected in the {@code X-Api-Key} header of every {@code /api/v1/links/**}
     *               request; blank (the default) leaves the API open and logs a warning at startup
     */
    public record Security(@Nullable String apiKey) {

        /** {@return whether an API key is configured, i.e. the management API requires authentication} */
        public boolean apiKeyConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Never prints the key itself. */
        @Override
        public String toString() {
            return "Security[apiKey=" + (apiKeyConfigured() ? "<redacted>" : "<unset>") + "]";
        }
    }
}
