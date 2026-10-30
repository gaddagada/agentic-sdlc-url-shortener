package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * The in-memory limiter against the shared {@link RateLimiterContract}, plus its bucket expiry.
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

        clock.advance(Duration.ofSeconds(3)); // time to full: the cache entry expires

        RateLimiter.Decision decision = limiter.tryConsume("client", 3);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isZero();
    }
}
