package com.agenticsdlc.shortener.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-by-line Java model of {@code src/main/resources/ratelimit/token-bucket.lua} on top of an in-memory "Redis":
 * hashes of string fields and per-key expiry, driven by explicit time. It lets the limiter and the script arithmetic
 * be unit-tested without Redis; {@code RedisRateLimiterIT} runs the same contract against the real script in Redis, so
 * a divergence between this model and the Lua code fails the build where Docker is available (CI).
 *
 * <p>Like Redis, it executes one script at a time ({@code synchronized}).
 */
public final class TokenBucketScriptSimulator {

    private static final long UNITS_PER_TOKEN = 60_000;

    private final Map<String, Map<String, String>> hashes = new HashMap<>();
    private final Map<String, Long> expiresAtMillis = new HashMap<>();
    private long serverTimeMillis;

    /**
     * Runs the script ({@code EVAL script 1 key argv...}).
     *
     * @param keys {@code KEYS}, exactly one bucket key
     * @param argv {@code ARGV}: capacity, refill per minute, now (epoch millis), tokens requested
     * @return {@code {allowed, remaining, waitMillis}} as Redis integer replies (Long)
     */
    public synchronized List<Long> eval(List<String> keys, List<String> argv) {
        if (keys.size() != 1 || argv.size() != 4) {
            throw new IllegalArgumentException("expected 1 key and 4 arguments");
        }
        String key = keys.getFirst();
        expireIfDue(key);
        // local capacity = tonumber(ARGV[1]) * units_per_token ...
        long capacity = Long.parseLong(argv.get(0)) * UNITS_PER_TOKEN;
        long rate = Long.parseLong(argv.get(1));
        long now = Long.parseLong(argv.get(2));
        long requested = Long.parseLong(argv.get(3)) * UNITS_PER_TOKEN;

        // local state = redis.call('HMGET', KEYS[1], 'units', 'ts')
        Map<String, String> hash = hashes.get(key);
        Long units = hash == null || hash.get("units") == null ? null : Long.valueOf(hash.get("units"));
        Long ts = hash == null || hash.get("ts") == null ? null : Long.valueOf(hash.get("ts"));
        if (units == null || ts == null) {
            units = capacity;
            ts = now;
        } else if (now > ts) {
            units = Math.min(capacity, units + saturatedMultiply(now - ts, rate));
            ts = now;
        }

        long allowed = 0;
        long wait = 0;
        if (units >= requested) {
            units = units - requested;
            allowed = 1;
        } else {
            wait = Math.floorDiv(requested - units + rate - 1, rate);
        }

        // redis.call('HSET', ...) and redis.call('PEXPIRE', ...)
        Map<String, String> stored = hashes.computeIfAbsent(key, k -> new HashMap<>());
        stored.put("units", Long.toString(units));
        stored.put("ts", Long.toString(ts));
        long ttl = Math.max(1, Math.floorDiv(capacity - units + rate - 1, rate));
        expiresAtMillis.put(key, serverTimeMillis + ttl);
        return List.of(allowed, Math.floorDiv(units, UNITS_PER_TOKEN), wait);
    }

    /**
     * Advances the simulated Redis server clock (key expiry only; the script uses the time passed in ARGV).
     *
     * @param millis milliseconds to advance
     */
    public synchronized void advanceServerTime(long millis) {
        serverTimeMillis += millis;
    }

    /**
     * Remaining time to live of a key, like {@code PTTL}.
     *
     * @param key key
     * @return milliseconds, or -2 when the key does not exist
     */
    public synchronized long pttl(String key) {
        expireIfDue(key);
        Long expiresAt = expiresAtMillis.get(key);
        return expiresAt == null ? -2 : expiresAt - serverTimeMillis;
    }

    /**
     * Field value of a stored bucket hash.
     *
     * @param key   key
     * @param field field
     * @return the value, or {@code null}
     */
    public synchronized String hget(String key, String field) {
        expireIfDue(key);
        Map<String, String> hash = hashes.get(key);
        return hash == null ? null : hash.get(field);
    }

    private void expireIfDue(String key) {
        Long expiresAt = expiresAtMillis.get(key);
        if (expiresAt != null && expiresAt <= serverTimeMillis) {
            hashes.remove(key);
            expiresAtMillis.remove(key);
        }
    }

    /** Lua numbers do not overflow; the result is clamped to the capacity anyway. */
    private static long saturatedMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return (high == 0 && low >= 0) ? low : Long.MAX_VALUE;
    }
}
