package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;

import com.agenticsdlc.shortener.service.RateLimiter;
import com.agenticsdlc.shortener.service.RateLimiter.Decision;
import com.agenticsdlc.shortener.support.TokenBucketScriptSimulator;

/**
 * {@link RedisRateLimiter} without Redis: the shared {@link RateLimiterContract} runs on the
 * {@link TokenBucketScriptSimulator} (a Java model of the Lua script), and the limiter's own concerns (arguments,
 * key layout, expiry, fail-open policy) are checked directly. {@code RedisRateLimiterIT} runs the same contract on a
 * real Redis.
 */
class RedisRateLimiterTest extends RateLimiterContract {

    private static final String PREFIX = "test:rl:";

    private final TokenBucketScriptSimulator redis = new TokenBucketScriptSimulator();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<List<String>> calls = new ArrayList<>();

    @Override
    protected RateLimiter limiter(int capacity, int refillPerMinute, Clock clock) {
        return new RedisRateLimiter((key, args) -> {
            calls.add(List.of(key, String.join(",", args)));
            return redis.eval(List.of(key), args);
        }, PREFIX, capacity, refillPerMinute, clock, meters);
    }

    @Test
    void passesKeyAndArgumentsInTheOrderTheScriptExpects() {
        RateLimiter limiter = limiter(20, 30, clock);

        limiter.tryConsume("203.0.113.9", 7);

        assertThat(calls).containsExactly(
                List.of(PREFIX + "203.0.113.9", "20,30," + clock.millis() + ",7"));
    }

    @Test
    void storesTheBucketAsExactUnitsAndExpiresItWhenItWouldBeFull() {
        RateLimiter limiter = limiter(2, 60, clock); // 60 units per millisecond, 120 000 units when full

        limiter.tryConsume("client");

        assertThat(redis.hget(PREFIX + "client", "units")).isEqualTo("60000");
        assertThat(redis.hget(PREFIX + "client", "ts")).isEqualTo(Long.toString(clock.millis()));
        assertThat(redis.pttl(PREFIX + "client")).isEqualTo(1_000); // one token short of full at 1 token/s
        redis.advanceServerTime(1_000);
        assertThat(redis.pttl(PREFIX + "client")).isEqualTo(-2);
        assertThat(limiter.tryConsume("client", 2).allowed()).isTrue(); // fresh, full bucket
    }

    @Test
    void failsOpenAndCountsWhenRedisIsUnavailable() {
        RedisRateLimiter limiter = new RedisRateLimiter((key, args) -> {
            throw new QueryTimeoutException("Redis command timed out");
        }, PREFIX, 5, 60, clock, meters);

        Decision first = limiter.tryConsume("client", 5);
        Decision second = limiter.tryConsume("client", 5);

        assertThat(first.allowed()).isTrue();
        assertThat(first.retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(second.allowed()).isTrue();
        assertThat(meters.counter(RedisRateLimiter.BACKEND_ERRORS_METRIC).count()).isEqualTo(2);
    }

    @Test
    void failsOpenOnAnUnexpectedReply() {
        RedisRateLimiter nullReply = new RedisRateLimiter((key, args) -> null, PREFIX, 5, 60, clock, meters);
        RedisRateLimiter shortReply = new RedisRateLimiter((key, args) -> List.of(1L), PREFIX, 5, 60, clock, meters);
        RedisRateLimiter wrongTypes = new RedisRateLimiter((key, args) -> List.of("1", "2", "3"), PREFIX, 5, 60, clock,
                meters);

        assertThat(nullReply.tryConsume("client").allowed()).isTrue();
        assertThat(shortReply.tryConsume("client").allowed()).isTrue();
        assertThat(wrongTypes.tryConsume("client").allowed()).isTrue();
        assertThat(meters.counter(RedisRateLimiter.BACKEND_ERRORS_METRIC).count()).isEqualTo(3);
    }

    @Test
    void theScriptIsOnTheClasspathAndDeclaresTheArgumentsThisClassPasses() throws IOException {
        String script = new ClassPathResource(RedisRateLimiter.SCRIPT_LOCATION).getContentAsString(
                StandardCharsets.UTF_8);

        assertThat(script).contains("KEYS[1]", "ARGV[1]", "ARGV[2]", "ARGV[3]", "ARGV[4]")
                .doesNotContain("ARGV[5]")
                .contains("local units_per_token = " + RateLimiter.UNITS_PER_TOKEN)
                .contains("HMGET", "HSET", "PEXPIRE");
    }
}
