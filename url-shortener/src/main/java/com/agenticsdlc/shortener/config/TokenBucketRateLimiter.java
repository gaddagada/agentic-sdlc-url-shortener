package com.agenticsdlc.shortener.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * In-memory token bucket per key (client IP).
 *
 * <p>Each bucket holds up to {@code capacity} tokens and refills continuously at {@code refillPerMinute}. Buckets
 * live in a size-bounded Caffeine cache and expire once idle long enough to be full again, which is equivalent to a
 * fresh bucket; memory therefore stays bounded no matter how many distinct clients appear. Time is read from the
 * injected {@link Clock} (also used as the cache ticker) so behaviour is deterministic in tests.
 *
 * <p>Single-node by design: a multi-node deployment needs a shared limiter (e.g. Redis) instead.
 */
public final class TokenBucketRateLimiter {

    static final long MAX_TRACKED_KEYS = 100_000;

    private final int capacity;
    private final double tokensPerMilli;
    private final Clock clock;
    private final Cache<String, Bucket> buckets;

    /**
     * Creates a limiter.
     *
     * @param capacity        maximum burst size, at least 1
     * @param refillPerMinute sustained rate in tokens per minute, at least 1
     * @param clock           time source
     */
    public TokenBucketRateLimiter(int capacity, int refillPerMinute, Clock clock) {
        if (capacity < 1 || refillPerMinute < 1) {
            throw new IllegalArgumentException("capacity and refillPerMinute must be at least 1");
        }
        this.capacity = capacity;
        this.tokensPerMilli = refillPerMinute / (double) TimeUnit.MINUTES.toMillis(1);
        this.clock = clock;
        Duration timeToFull = Duration.ofMillis((long) Math.ceil(capacity / tokensPerMilli));
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterAccess(timeToFull)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    /**
     * Takes one token from the bucket of {@code key}.
     *
     * @param key client identifier
     * @return whether the request is allowed, the tokens left and, when denied, how long to wait
     */
    public Decision tryConsume(String key) {
        long now = clock.millis();
        return buckets.get(key, k -> new Bucket(capacity, now)).tryConsume(now);
    }

    /** {@return the configured bucket capacity} */
    public int capacity() {
        return capacity;
    }

    /**
     * Outcome of a consumption attempt.
     *
     * @param allowed    whether a token was taken
     * @param remaining  whole tokens left after this attempt
     * @param retryAfter time until the next token is available ({@link Duration#ZERO} when allowed)
     */
    public record Decision(boolean allowed, long remaining, Duration retryAfter) {

        /** {@return {@link #retryAfter} rounded up to whole seconds, at least 1, for the Retry-After header} */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        }
    }

    private final class Bucket {

        private double tokens;
        private long lastRefillMillis;

        private Bucket(double tokens, long nowMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = nowMillis;
        }

        private synchronized Decision tryConsume(long nowMillis) {
            refill(nowMillis);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, (long) Math.floor(tokens), Duration.ZERO);
            }
            long waitMillis = (long) Math.ceil((1 - tokens) / tokensPerMilli);
            return new Decision(false, 0, Duration.ofMillis(waitMillis));
        }

        private void refill(long nowMillis) {
            long elapsed = nowMillis - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerMilli);
                lastRefillMillis = nowMillis;
            }
        }
    }
}
