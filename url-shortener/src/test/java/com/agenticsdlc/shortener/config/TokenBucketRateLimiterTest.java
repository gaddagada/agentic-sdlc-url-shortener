package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * The in-memory limiter against the shared {@link RateLimiterContract}, plus its bucket expiry and its bounded store.
 */
class TokenBucketRateLimiterTest extends RateLimiterContract {

    @Override
    protected RateLimiter limiter(int capacity, int refillPerMinute, Clock clock) {
        return new TokenBucketRateLimiter(capacity, refillPerMinute, clock);
    }

    @Test
    void anIdleBucketThatWouldBeFullIsEquivalentToAFreshOne() {
        RateLimiter limiter = limiter(3, 60, clock);
        drain(limiter, "client", 3);

        clock.advance(Duration.ofSeconds(3)); // time to full: the bucket is dropped

        RateLimiter.Decision decision = limiter.tryConsume("client", 3);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isZero();
    }

    @Test
    void aClientThatDrainedItsBucketIsNotResetByManyOtherClients() {
        RateLimiter limiter = limiter(20, 1, clock); // one token per minute: a drained bucket stays drained
        for (int i = 0; i < TokenBucketRateLimiter.MAX_TRACKED_KEYS; i++) {
            limiter.tryConsume("earlier-" + i, 1);
        }
        assertThat(limiter.tryConsume("burst", 20).allowed()).isTrue();
        for (int i = 0; i < 50_000; i++) {
            limiter.tryConsume("later-" + i, 1);
        }

        RateLimiter.Decision again = limiter.tryConsume("burst", 1);
        assertThat(again.allowed()).as("the drained bucket was kept, not replaced by a fresh one").isFalse();
        assertThat(again.retryAfter()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void beyondTheLimitTheBucketThatWillBeFullSoonestIsEvictedFirst() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 60, clock, 3); // one token per second
        assertThat(limiter.tryConsume("steady", 10).allowed()).isTrue(); // full again in 10 s
        assertThat(limiter.tryConsume("half", 5).allowed()).isTrue();    // in 5 s
        assertThat(limiter.tryConsume("light", 1).allowed()).isTrue();   // in 1 s

        assertThat(limiter.tryConsume("other", 2).allowed()).isTrue();   // in 2 s: "light" goes first
        assertThat(limiter.trackedKeys()).isEqualTo(3);
        for (int i = 0; i < 1_000; i++) {
            assertThat(limiter.tryConsume("flood-" + i, 1).allowed()).as("a new client gets a fresh bucket").isTrue();
        }
        assertThat(limiter.trackedKeys()).isEqualTo(3);

        RateLimiter.Decision steady = limiter.tryConsume("steady", 1);
        assertThat(steady.allowed()).as("a burst of new keys never resets a drained bucket").isFalse();
        assertThat(steady.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(limiter.tryConsume("half", 6).allowed()).as("half full: 5 tokens left").isFalse();
        assertThat(limiter.tryConsume("half", 5).allowed()).isTrue();
        assertThat(limiter.tryConsume("light", 10).allowed()).as("evicted, and it would have been full in 1 s")
                .isTrue();
    }

    @Test
    void aSteadyClientIsNeverResetByABurstOfNewKeys() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, clock, 10);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryConsume("steady", 1).allowed()).isTrue();
        }
        for (int i = 0; i < 10_000; i++) {
            limiter.tryConsume("flood-" + i, 1);
        }
        assertThat(limiter.trackedKeys()).as("the store stays bounded").isLessThanOrEqualTo(10);

        RateLimiter.Decision steady = limiter.tryConsume("steady", 1);
        assertThat(steady.allowed()).as("10 000 one-off keys never reset the drained bucket").isFalse();
        assertThat(steady.retryAfter()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void aSteadyClientIsNotResetByMoreNewKeysThanTheStoreHolds() {
        // with the previous frequency-based cache (W-TinyLFU), the drained bucket was evicted once the frequency
        // sketch aged (every ten times the maximum size of accesses) and the client got a fresh, full bucket
        RateLimiter limiter = limiter(10, 1, clock);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryConsume("steady", 1).allowed()).isTrue();
        }
        for (int i = 0; i < 1_500_000; i++) {
            limiter.tryConsume("flood-" + i, 1);
        }
        assertThat(limiter.tryConsume("steady", 1).allowed()).as("still drained after 1.5 million new keys")
                .isFalse();
    }

    @Test
    void aNewClientIsLimitedEvenWhenTheStoreIsFullOfDrainedBuckets() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, clock, 3);
        for (String drained : new String[] {"a", "b", "c"}) {
            for (int i = 0; i < 10; i++) {
                limiter.tryConsume(drained, 1);
            }
        }

        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.tryConsume("newcomer", 1).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).as("its own bucket is tracked from the first request, never a fresh one per request")
                .isEqualTo(10);
        assertThat(limiter.trackedKeys()).isEqualTo(3);
    }

    @Test
    void bucketsAreDroppedOnceFullSoMemoryOnlyHoldsBucketsInUse() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 60, clock, 100);
        limiter.tryConsume("a", 1);
        limiter.tryConsume("b", 10);
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        limiter.tryConsume("c", 1);
        assertThat(limiter.trackedKeys()).as("a is full again and forgotten").isEqualTo(2);

        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryConsume("b", 10).allowed()).isTrue();
        assertThat(limiter.trackedKeys()).as("only b, drained again").isEqualTo(1);
    }
}
