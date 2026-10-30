package com.agenticsdlc.shortener.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * In-memory token bucket per key (client IP, or IPv6 prefix): the default {@link RateLimiter}
 * ({@code shortener.rate-limit.backend=memory}).
 *
 * <p>Each bucket holds up to {@code capacity} tokens and refills continuously at {@code refillPerMinute}. Amounts are
 * exact integers in {@link RateLimiter#UNITS_PER_TOKEN units}, the same arithmetic as the Redis Lua script of
 * {@link RedisRateLimiter}. Time is read from the injected {@link Clock} so behaviour is deterministic in tests.</p>
 *
 * <p>Memory is bounded without ever giving a client more than a full bucket's worth back. A bucket that has refilled
 * completely is equivalent to a fresh one, so it is dropped as soon as it would be full (at most the time to refill a
 * full bucket after its last use: the same bound as an expire-after-access of that duration). Beyond
 * {@value #MAX_TRACKED_KEYS} buckets, the bucket that will be full soonest is evicted first, never the bucket of the
 * request being served: forgetting the soonest-full bucket hands back the fewest tokens, a client that drained its
 * bucket is kept while any bucket closer to full exists (a burst of new clients, or a client rotating addresses,
 * evicts its own nearly full buckets first and never resets a drained one), and a new client is always tracked, so it
 * is limited like everyone else even when the store is full.</p>
 *
 * <p>Single-node by design: with several instances every node has its own buckets, so the effective limit is
 * multiplied by the number of nodes. Use {@link RedisRateLimiter} ({@code backend=redis}) for a shared limit.</p>
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    static final long MAX_TRACKED_KEYS = 100_000;

    /** Soonest full first; ties (same instant) by creation order, so the order is total and deterministic. */
    private static final Comparator<Bucket> FULL_AT_ORDER = Comparator.comparingLong((Bucket b) -> b.fullAtMillis)
            .thenComparingLong(b -> b.sequence);

    private final int capacity;
    private final long capacityUnits;
    private final long unitsPerMilli;
    private final long millisToFull;
    private final long maxTrackedKeys;
    private final Clock clock;
    /** Guarded by {@code this}. */
    private final Map<String, Bucket> buckets = new HashMap<>();
    /** The buckets of {@link #buckets} ordered by when they will be full; guarded by {@code this}. */
    private final NavigableSet<Bucket> byFullAt = new TreeSet<>(FULL_AT_ORDER);
    /** Guarded by {@code this}. */
    private long sequence;

    /**
     * Creates a limiter.
     *
     * @param capacity        maximum burst size, 1..{@value RateLimiter#MAX_RATE}
     * @param refillPerMinute sustained rate in tokens per minute, 1..{@value RateLimiter#MAX_RATE}
     * @param clock           time source
     */
    public TokenBucketRateLimiter(int capacity, int refillPerMinute, Clock clock) {
        this(capacity, refillPerMinute, clock, MAX_TRACKED_KEYS);
    }

    /**
     * Creates a limiter that tracks at most {@code maxTrackedKeys} buckets (tests).
     *
     * @param capacity        maximum burst size
     * @param refillPerMinute sustained rate in tokens per minute
     * @param clock           time source
     * @param maxTrackedKeys  maximum number of buckets kept (at least 1)
     */
    TokenBucketRateLimiter(int capacity, int refillPerMinute, Clock clock, long maxTrackedKeys) {
        RateLimiter.checkConfiguration(capacity, refillPerMinute);
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be positive");
        }
        this.capacity = capacity;
        this.capacityUnits = capacity * UNITS_PER_TOKEN;
        this.unitsPerMilli = refillPerMinute;
        this.millisToFull = ceilDiv(capacityUnits, unitsPerMilli);
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
    }

    @Override
    public Decision tryConsume(String client, int tokens) {
        RateLimiter.checkTokens(tokens, capacity);
        long now = clock.millis();
        synchronized (this) {
            dropFullBuckets(now);
            Bucket bucket = buckets.get(client);
            if (bucket == null) {
                bucket = new Bucket(client, ++sequence, capacityUnits, now);
                buckets.put(client, bucket);
            } else {
                byFullAt.remove(bucket); // its position changes with what it consumes
            }
            Decision decision = bucket.tryConsume(tokens * UNITS_PER_TOKEN, now);
            byFullAt.add(bucket);
            evictBeyondLimit(bucket);
            return decision;
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    /** Number of buckets currently kept (diagnostics and tests). */
    synchronized int trackedKeys() {
        return buckets.size();
    }

    /** Drops every bucket that is full at {@code now}: it is equivalent to a fresh bucket. */
    private void dropFullBuckets(long now) {
        while (!byFullAt.isEmpty() && byFullAt.first().fullAtMillis <= now) {
            buckets.remove(byFullAt.pollFirst().key);
        }
    }

    /**
     * Evicts the buckets that will be full soonest until the limit holds, skipping the bucket just used: evicting it
     * would hand its client a fresh bucket on every request, so a new client would never be limited while the store
     * is full.
     */
    private void evictBeyondLimit(Bucket current) {
        Iterator<Bucket> soonestFirst = byFullAt.iterator();
        while (buckets.size() > maxTrackedKeys && soonestFirst.hasNext()) {
            Bucket candidate = soonestFirst.next();
            if (candidate != current) {
                soonestFirst.remove();
                buckets.remove(candidate.key);
            }
        }
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private final class Bucket {

        private final String key;
        private final long sequence;
        private long units;
        private long lastRefillMillis;
        /** When the bucket will be full again without further use (it never changes while it is not used). */
        private long fullAtMillis;

        private Bucket(String key, long sequence, long units, long nowMillis) {
            this.key = key;
            this.sequence = sequence;
            this.units = units;
            this.lastRefillMillis = nowMillis;
            this.fullAtMillis = nowMillis;
        }

        private Decision tryConsume(long requestedUnits, long nowMillis) {
            refill(nowMillis);
            Decision decision;
            if (units >= requestedUnits) {
                units -= requestedUnits;
                decision = new Decision(true, units / UNITS_PER_TOKEN, Duration.ZERO);
            } else {
                long waitMillis = ceilDiv(requestedUnits - units, unitsPerMilli);
                decision = new Decision(false, units / UNITS_PER_TOKEN, Duration.ofMillis(waitMillis));
            }
            fullAtMillis = lastRefillMillis + ceilDiv(capacityUnits - units, unitsPerMilli);
            return decision;
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
