package com.agenticsdlc.shortener.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Token-bucket rate limiter SPI, keyed per client (the client IP resolved by {@link ClientIpResolver}).
 *
 * <p>Every client owns a bucket of {@link #capacity()} tokens that refills continuously at a fixed rate. A
 * consumption is all-or-nothing: either all requested tokens are taken, or none and the decision says how long to
 * wait. Implementations:
 * <ul>
 *   <li>{@code TokenBucketRateLimiter}: in-memory, single node ({@code shortener.rate-limit.backend=memory}, the
 *       default);</li>
 *   <li>{@code RedisRateLimiter}: shared by all nodes through an atomic Lua script
 *       ({@code shortener.rate-limit.backend=redis}).</li>
 * </ul>
 * Both keep amounts in exact integer units (see {@link #UNITS_PER_TOKEN}), so they take identical decisions for the
 * same sequence of calls and clock readings.
 *
 * <p>Link creation is charged one token per link: {@code POST /api/v1/links} costs one token (charged by the servlet
 * filter), {@code POST /api/v1/links/bulk} costs one token per item (charged by {@link BulkLinkService}).
 */
public interface RateLimiter {

    /**
     * Bucket amounts are kept in units of 1/60 000 token. A refill of {@code refillPerMinute} tokens per minute is
     * then exactly {@code refillPerMinute} units per millisecond, so all bucket arithmetic is exact integer
     * arithmetic (no floating-point drift, identical results in Java and in the Redis Lua script).
     */
    long UNITS_PER_TOKEN = 60_000;

    /** Upper bound of capacity and refill rate; keeps every unit amount far below 2^53 (exact as a Lua number). */
    int MAX_RATE = 1_000_000;

    /**
     * Takes {@code tokens} tokens from the bucket of {@code client}, or none.
     *
     * @param client client identifier
     * @param tokens tokens to take, between 1 and {@link #capacity()}
     * @return whether the tokens were taken, the whole tokens left and, when denied, how long to wait
     * @throws IllegalArgumentException when {@code tokens} is outside 1..{@link #capacity()}: such a request could
     *                                  never be granted, callers must reject it up front
     */
    Decision tryConsume(String client, int tokens);

    /**
     * Takes one token from the bucket of {@code client}.
     *
     * @param client client identifier
     * @return the decision
     */
    default Decision tryConsume(String client) {
        return tryConsume(client, 1);
    }

    /** {@return the bucket capacity: the burst size and the largest number of tokens one call may take} */
    int capacity();

    /**
     * Validates a token request against a capacity; shared by the implementations.
     *
     * @param tokens   requested tokens
     * @param capacity bucket capacity
     * @throws IllegalArgumentException when {@code tokens} is outside 1..capacity
     */
    static void checkTokens(int tokens, int capacity) {
        if (tokens < 1 || tokens > capacity) {
            throw new IllegalArgumentException("tokens must be between 1 and " + capacity + ", was " + tokens);
        }
    }

    /**
     * Validates a limiter configuration; shared by the implementations.
     *
     * @param capacity        bucket capacity
     * @param refillPerMinute refill rate in tokens per minute
     * @throws IllegalArgumentException when either value is outside 1..{@link #MAX_RATE}
     */
    static void checkConfiguration(int capacity, int refillPerMinute) {
        if (capacity < 1 || refillPerMinute < 1 || capacity > MAX_RATE || refillPerMinute > MAX_RATE) {
            throw new IllegalArgumentException("capacity and refillPerMinute must be between 1 and " + MAX_RATE);
        }
    }

    /**
     * Outcome of a consumption attempt.
     *
     * @param allowed    whether the tokens were taken
     * @param remaining  whole tokens left after this attempt
     * @param retryAfter time until enough tokens are available ({@link Duration#ZERO} when allowed)
     */
    record Decision(boolean allowed, long remaining, Duration retryAfter) {

        /** Validates the components. */
        public Decision {
            Objects.requireNonNull(retryAfter, "retryAfter");
        }

        /** {@return {@link #retryAfter} rounded up to whole seconds, at least 1, for the Retry-After header} */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        }
    }
}
