package com.agenticsdlc.shortener.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * {@link RateLimiter} fake with a fixed number of available tokens (no refill) that records every attempt as
 * {@code client:tokens}.
 */
public final class RecordingRateLimiter implements RateLimiter {

    private final int capacity;
    private final List<String> attempts = new CopyOnWriteArrayList<>();
    private long available;

    /**
     * Creates the fake.
     *
     * @param capacity  bucket capacity
     * @param available tokens currently in the bucket
     */
    public RecordingRateLimiter(int capacity, long available) {
        this.capacity = capacity;
        this.available = available;
    }

    @Override
    public synchronized Decision tryConsume(String client, int tokens) {
        RateLimiter.checkTokens(tokens, capacity);
        attempts.add(client + ":" + tokens);
        if (tokens > available) {
            return new Decision(false, available, Duration.ofSeconds(3L * (tokens - available)));
        }
        available -= tokens;
        return new Decision(true, available, Duration.ZERO);
    }

    @Override
    public int capacity() {
        return capacity;
    }

    /** {@return every consumption attempt as {@code client:tokens}, in order} */
    public List<String> attempts() {
        return List.copyOf(attempts);
    }
}
