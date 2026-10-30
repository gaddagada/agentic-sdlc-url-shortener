package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.service.RateLimiter;
import com.agenticsdlc.shortener.service.RateLimiter.Decision;
import com.agenticsdlc.shortener.support.MutableClock;

/**
 * Behaviour every {@link RateLimiter} backend must show, run against the in-memory limiter, the Redis limiter on the
 * Lua script simulator and the Redis limiter on a real Redis ({@code RedisRateLimiterIT}). Because all of them must
 * produce exactly these decisions (exact integer arithmetic), the backends are interchangeable.
 */
abstract class RateLimiterContract {

    protected final MutableClock clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));

    /**
     * Creates a fresh limiter with its own, empty buckets.
     *
     * @param capacity        bucket capacity
     * @param refillPerMinute refill rate
     * @param clock           time source
     * @return the limiter under test
     */
    protected abstract RateLimiter limiter(int capacity, int refillPerMinute, Clock clock);

    @Test
    void allowsUpToCapacityThenRejectsWithRetryAfter() {
        RateLimiter limiter = limiter(2, 60, clock); // one token per second

        Decision first = limiter.tryConsume("client");
        Decision second = limiter.tryConsume("client");
        Decision third = limiter.tryConsume("client");

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();
        assertThat(third.allowed()).isFalse();
        assertThat(third.remaining()).isZero();
        assertThat(third.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(third.retryAfterSeconds()).isEqualTo(1);
        assertThat(limiter.capacity()).isEqualTo(2);
    }

    @Test
    void refillsContinuouslyOverTime() {
        RateLimiter limiter = limiter(2, 60, clock);
        drain(limiter, "client", 2);

        clock.advance(Duration.ofMillis(500));
        Decision halfway = limiter.tryConsume("client");
        clock.advance(Duration.ofMillis(500));
        Decision refilled = limiter.tryConsume("client");

        assertThat(halfway.allowed()).isFalse();
        assertThat(halfway.retryAfter()).isEqualTo(Duration.ofMillis(500));
        assertThat(refilled.allowed()).isTrue();
    }

    @Test
    void neverAccumulatesMoreThanCapacity() {
        RateLimiter limiter = limiter(2, 60, clock);
        drain(limiter, "client", 2);

        clock.advance(Duration.ofHours(1));

        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isFalse();
    }

    @Test
    void keepsSeparateBucketsPerKey() {
        RateLimiter limiter = limiter(1, 1, clock);

        assertThat(limiter.tryConsume("a").allowed()).isTrue();
        assertThat(limiter.tryConsume("a").allowed()).isFalse();
        assertThat(limiter.tryConsume("b").allowed()).isTrue();
        assertThat(limiter.tryConsume("2001:db8::1").allowed()).isTrue();
    }

    @Test
    void roundsRetryAfterUpToWholeSeconds() {
        RateLimiter limiter = limiter(1, 1, clock); // one token per minute
        drain(limiter, "client", 1);

        Decision denied = limiter.tryConsume("client");
        clock.advance(Duration.ofMillis(59_500));
        Decision almost = limiter.tryConsume("client");

        assertThat(denied.retryAfterSeconds()).isEqualTo(60);
        assertThat(almost.allowed()).isFalse();
        assertThat(almost.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void takesSeveralTokensAtOnceAllOrNothing() {
        RateLimiter limiter = limiter(5, 60, clock);

        Decision three = limiter.tryConsume("client", 3);
        Decision threeMore = limiter.tryConsume("client", 3);
        Decision two = limiter.tryConsume("client", 2);

        assertThat(three.allowed()).isTrue();
        assertThat(three.remaining()).isEqualTo(2);
        assertThat(threeMore.allowed()).isFalse();
        assertThat(threeMore.remaining()).isEqualTo(2); // a denied request takes nothing
        assertThat(threeMore.retryAfter()).isEqualTo(Duration.ofSeconds(1)); // one token missing
        assertThat(two.allowed()).isTrue();
        assertThat(two.remaining()).isZero();
    }

    @Test
    void retryAfterCoversEveryMissingToken() {
        RateLimiter limiter = limiter(10, 60, clock);
        drain(limiter, "client", 10);

        Decision four = limiter.tryConsume("client", 4);
        clock.advance(Duration.ofSeconds(3));
        Decision stillOneShort = limiter.tryConsume("client", 4);
        clock.advance(Duration.ofSeconds(1));
        Decision granted = limiter.tryConsume("client", 4);

        assertThat(four.allowed()).isFalse();
        assertThat(four.retryAfter()).isEqualTo(Duration.ofSeconds(4));
        assertThat(stillOneShort.allowed()).isFalse();
        assertThat(stillOneShort.remaining()).isEqualTo(3);
        assertThat(stillOneShort.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(granted.allowed()).isTrue();
        assertThat(granted.remaining()).isZero();
    }

    @Test
    void usesExactArithmeticForRatesThatDoNotDivideAMinute() {
        RateLimiter limiter = limiter(3, 7, clock); // 7 tokens per minute: one every 8571.43 ms
        drain(limiter, "client", 3);

        clock.advance(Duration.ofMillis(8_571));
        Decision justShort = limiter.tryConsume("client");
        clock.advance(Duration.ofMillis(1));
        Decision granted = limiter.tryConsume("client");

        assertThat(justShort.allowed()).isFalse();
        assertThat(justShort.retryAfter()).isEqualTo(Duration.ofMillis(1));
        assertThat(granted.allowed()).isTrue();
        assertThat(granted.remaining()).isZero();
    }

    @Test
    void anEarlierClockReadingNeitherRefillsNorRewindsTheBucket() {
        RateLimiter limiter = limiter(1, 60, clock);
        drain(limiter, "client", 1);

        clock.advance(Duration.ofSeconds(-5)); // e.g. another node whose clock is behind
        Decision behind = limiter.tryConsume("client");
        clock.advance(Duration.ofSeconds(6)); // one second after the last refill
        Decision granted = limiter.tryConsume("client");

        assertThat(behind.allowed()).isFalse();
        assertThat(behind.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(granted.allowed()).isTrue();
    }

    @Test
    void rejectsTokenCountsThatCouldNeverBeGranted() {
        RateLimiter limiter = limiter(3, 60, clock);

        assertThatThrownBy(() -> limiter.tryConsume("client", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryConsume("client", 4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(limiter.tryConsume("client", 3).allowed()).isTrue();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> limiter(0, 1, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, 0, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(RateLimiter.MAX_RATE + 1, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantsExactlyCapacityUnderConcurrency() throws Exception {
        int capacity = 50;
        RateLimiter limiter = limiter(capacity, 1, clock);
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

    protected static void drain(RateLimiter limiter, String key, int tokens) {
        for (int i = 0; i < tokens; i++) {
            assertThat(limiter.tryConsume(key).allowed()).isTrue();
        }
    }
}
