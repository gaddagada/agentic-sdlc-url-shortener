package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.support.MutableClock;

class TokenBucketRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));

    @Test
    void allowsUpToCapacityThenRejectsWithRetryAfter() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 60, clock); // one token per second

        TokenBucketRateLimiter.Decision first = limiter.tryConsume("client");
        TokenBucketRateLimiter.Decision second = limiter.tryConsume("client");
        TokenBucketRateLimiter.Decision third = limiter.tryConsume("client");

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(third.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsContinuouslyOverTime() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 60, clock);
        drain(limiter, "client", 2);

        clock.advance(Duration.ofMillis(500));
        TokenBucketRateLimiter.Decision halfway = limiter.tryConsume("client");
        clock.advance(Duration.ofMillis(500));
        TokenBucketRateLimiter.Decision refilled = limiter.tryConsume("client");

        assertThat(halfway.allowed()).isFalse();
        assertThat(halfway.retryAfter()).isEqualTo(Duration.ofMillis(500));
        assertThat(refilled.allowed()).isTrue();
    }

    @Test
    void neverAccumulatesMoreThanCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 60, clock);
        drain(limiter, "client", 2);

        clock.advance(Duration.ofHours(1));

        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isFalse();
    }

    @Test
    void keepsSeparateBucketsPerKey() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, clock);

        assertThat(limiter.tryConsume("a").allowed()).isTrue();
        assertThat(limiter.tryConsume("a").allowed()).isFalse();
        assertThat(limiter.tryConsume("b").allowed()).isTrue();
    }

    @Test
    void roundsRetryAfterUpToWholeSeconds() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, clock); // one token per minute
        drain(limiter, "client", 1);

        TokenBucketRateLimiter.Decision denied = limiter.tryConsume("client");
        clock.advance(Duration.ofMillis(59_500));
        TokenBucketRateLimiter.Decision almost = limiter.tryConsume("client");

        assertThat(denied.retryAfterSeconds()).isEqualTo(60);
        assertThat(almost.allowed()).isFalse();
        assertThat(almost.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void grantsExactlyCapacityUnderConcurrency() throws Exception {
        int capacity = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1, clock);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryConsume("client").allowed();
                }));
            }
            start.countDown();
        }

        long allowed = 0;
        for (Future<Boolean> result : results) {
            allowed += result.get() ? 1 : 0;
        }
        assertThat(allowed).isEqualTo(capacity);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, 0, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void drain(TokenBucketRateLimiter limiter, String key, int tokens) {
        for (int i = 0; i < tokens; i++) {
            assertThat(limiter.tryConsume(key).allowed()).isTrue();
        }
    }
}
