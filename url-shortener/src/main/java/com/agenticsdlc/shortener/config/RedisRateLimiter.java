package com.agenticsdlc.shortener.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.agenticsdlc.shortener.service.RateLimiter;

/**
 * {@link RateLimiter} shared by every application node through Redis ({@code shortener.rate-limit.backend=redis}).
 *
 * <p>Each client has one Redis hash ({@code <key-prefix><client>}) updated by the Lua script
 * {@code ratelimit/token-bucket.lua}. Redis executes a script atomically, so the read-refill-take-write sequence
 * cannot interleave with another node's request, and the bucket key expires once it would be full again (bounded
 * memory). The script uses the same integer token-bucket arithmetic as {@link TokenBucketRateLimiter}; both backends
 * take identical decisions for the same calls and clock readings (verified by the shared contract tests).
 *
 * <p>Time comes from the application {@link Clock} (passed as a script argument), which keeps the script
 * deterministic and testable. Nodes should be NTP-synchronized; a node whose clock is behind the last refill neither
 * refills nor rewinds the bucket, so clock skew can delay a refill but never grant extra tokens.
 *
 * <p><b>Failure policy: fail open.</b> When Redis is unreachable or answers unexpectedly, the request is allowed
 * (link creation stays available; reported with 0 remaining tokens), {@value #BACKEND_ERRORS_METRIC} is incremented
 * and a warning is logged at most once per minute. Keep {@code spring.data.redis.timeout} short (500 ms in
 * {@code application.yml}) so an unreachable Redis does not stall requests.
 */
public final class RedisRateLimiter implements RateLimiter {

    /** Default key prefix of the bucket hashes. */
    public static final String DEFAULT_KEY_PREFIX = "shortener:rate-limit:";
    static final String BACKEND_ERRORS_METRIC = "shortener.rate-limit.backend.errors";
    static final String SCRIPT_LOCATION = "ratelimit/token-bucket.lua";

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(new ClassPathResource(SCRIPT_LOCATION), List.class);
    private static final long WARN_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final ScriptExecutor executor;
    private final String keyPrefix;
    private final int capacity;
    private final int refillPerMinute;
    private final Clock clock;
    private final Counter backendErrors;
    private final AtomicLong nextWarningAt = new AtomicLong(Long.MIN_VALUE);

    /**
     * Creates a limiter executing the Lua script through {@code redis}.
     *
     * @param redis           Redis access (Lettuce, auto-configured from {@code spring.data.redis.*})
     * @param keyPrefix       prefix of the bucket keys
     * @param capacity        maximum burst size, 1..{@value RateLimiter#MAX_RATE}
     * @param refillPerMinute sustained rate in tokens per minute, 1..{@value RateLimiter#MAX_RATE}
     * @param clock           time source passed to the script
     * @param meterRegistry   registry of {@value #BACKEND_ERRORS_METRIC}
     */
    public RedisRateLimiter(StringRedisTemplate redis, String keyPrefix, int capacity, int refillPerMinute,
                            Clock clock, MeterRegistry meterRegistry) {
        this(scriptExecutor(redis), keyPrefix, capacity, refillPerMinute, clock, meterRegistry);
    }

    RedisRateLimiter(ScriptExecutor executor, String keyPrefix, int capacity, int refillPerMinute, Clock clock,
                     MeterRegistry meterRegistry) {
        RateLimiter.checkConfiguration(capacity, refillPerMinute);
        this.executor = executor;
        this.keyPrefix = keyPrefix;
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
        this.clock = clock;
        this.backendErrors = Counter.builder(BACKEND_ERRORS_METRIC)
                .description("Rate-limit decisions that failed open because the Redis backend was unavailable")
                .register(meterRegistry);
    }

    @Override
    public Decision tryConsume(String client, int tokens) {
        RateLimiter.checkTokens(tokens, capacity);
        List<String> args = List.of(Integer.toString(capacity), Integer.toString(refillPerMinute),
                Long.toString(clock.millis()), Integer.toString(tokens));
        List<?> reply;
        try {
            reply = executor.execute(keyPrefix + client, args);
        } catch (RuntimeException backendFailure) {
            return failOpen(backendFailure.toString());
        }
        if (reply == null || reply.size() != 3 || !(reply.get(0) instanceof Long allowed)
                || !(reply.get(1) instanceof Long remaining) || !(reply.get(2) instanceof Long waitMillis)) {
            return failOpen("unexpected script reply " + reply);
        }
        return new Decision(allowed == 1L, remaining, Duration.ofMillis(waitMillis));
    }

    @Override
    public int capacity() {
        return capacity;
    }

    private Decision failOpen(String reason) {
        backendErrors.increment();
        long now = clock.millis();
        long next = nextWarningAt.get();
        if (now >= next && nextWarningAt.compareAndSet(next, now + WARN_INTERVAL_MILLIS)) {
            log.warn("rate limiter backend unavailable, allowing request (fail open): {}", reason);
        }
        return new Decision(true, 0, Duration.ZERO);
    }

    private static ScriptExecutor scriptExecutor(StringRedisTemplate redis) {
        return (key, args) -> redis.execute(SCRIPT, List.of(key), args.toArray());
    }

    /**
     * Runs the token-bucket script: {@code EVALSHA <script> 1 key args...} (falling back to {@code EVAL}). A seam so
     * that the limiter can be unit-tested against a simulator of the script, without Redis.
     */
    @FunctionalInterface
    interface ScriptExecutor {

        /**
         * Executes the script.
         *
         * @param key  {@code KEYS[1]}
         * @param args {@code ARGV}
         * @return the script reply, a list of three integers
         */
        @Nullable List<?> execute(String key, List<String> args);
    }
}
