package com.agenticsdlc.shortener.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * In-memory token bucket per key (client IP): the default {@link RateLimiter}
 * ({@code shortener.rate-limit.backend=memory}).
 *
 * <p>Each bucket holds up to {@code capacity} tokens and refills continuously at {@code refillPerMinute}. Amounts are
 * exact integers in {@link RateLimiter#UNITS_PER_TOKEN units}, the same arithmetic as the Redis Lua script of
 * {@link RedisRateLimiter}. Buckets live in a size-bounded Caffeine cache and expire once idle long enough to be full
 * again, which is equivalent to a fresh bucket; memory therefore stays bounded no matter how many distinct clients
 * appear. Time is read from the injected {@link Clock} (also used as the cache ticker) so behaviour is deterministic
 * in tests.
 *
 * <p>Single-node by design: with several instances every node has its own buckets, so the effective limit is
 * multiplied by the number of nodes. Use {@link RedisRateLimiter} ({@code backend=redis}) for a shared limit.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    static final long MAX_TRACKED_KEYS = 100_000;

    private final int capacity;
    private final long capacityUnits;
    private final long unitsPerMilli;
    private final long millisToFull;
    private final Clock clock;
    private final Cache<String, Bucket> buckets;

    /**
     * Creates a limiter.
     *
     * @param capacity        maximum burst size, 1..{@value RateLimiter#MAX_RATE}
     * @param refillPerMinute sustained rate in tokens per minute, 1..{@value RateLimiter#MAX_RATE}
     * @param clock           time source
     */
    public TokenBucketRateLimiter(int capacity, int refillPerMinute, Clock clock) {
        RateLimiter.checkConfiguration(capacity, refillPerMinute);
        this.capacity = capacity;
        this.capacityUnits = capacity * UNITS_PER_TOKEN;
        this.unitsPerMilli = refillPerMinute;
        this.millisToFull = ceilDiv(capacityUnits, unitsPerMilli);
        this.clock = clock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterAccess(Duration.ofMillis(millisToFull))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    @Override
    public Decision tryConsume(String client, int tokens) {
        RateLimiter.checkTokens(tokens, capacity);
        long now = clock.millis();
        return buckets.get(client, k -> new Bucket(capacityUnits, now)).tryConsume(tokens * UNITS_PER_TOKEN, now);
    }

    @Override
    public int capacity() {
        return capacity;
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private final class Bucket {

        private long units;
        private long lastRefillMillis;

        private Bucket(long units, long nowMillis) {
            this.units = units;
            this.lastRefillMillis = nowMillis;
        }

        private synchronized Decision tryConsume(long requestedUnits, long nowMillis) {
            refill(nowMillis);
            if (units >= requestedUnits) {
                units -= requestedUnits;
                return new Decision(true, units / UNITS_PER_TOKEN, Duration.ZERO);
            }
            long waitMillis = ceilDiv(requestedUnits - units, unitsPerMilli);
            return new Decision(false, units / UNITS_PER_TOKEN, Duration.ofMillis(waitMillis));
        }

        /** An earlier clock reading neither refills nor rewinds the bucket. */
        private void refill(long nowMillis) {
            long elapsed = nowMillis - lastRefillMillis;
            if (elapsed > 0) {
                // From millisToFull on the bucket is full; below it the product cannot overflow.
                units = elapsed >= millisToFull
                        ? capacityUnits
                        : Math.min(capacityUnits, units + elapsed * unitsPerMilli);
                lastRefillMillis = nowMillis;
            }
        }
    }
}
