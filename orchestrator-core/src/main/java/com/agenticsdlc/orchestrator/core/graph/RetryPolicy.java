package com.agenticsdlc.orchestrator.core.graph;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded retry policy of a stage.
 *
 * @param maxAttempts maximum attempts per stage activation, including the first one (at least 1)
 * @param initialBackoff backoff before the first retry
 * @param multiplier exponential backoff multiplier (at least 1.0)
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier) {

    /** Default initial backoff. */
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(2);

    /** Default multiplier. */
    public static final double DEFAULT_MULTIPLIER = 2.0;

    /**
     * Validates the policy.
     */
    public RetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
    }

    /**
     * Returns a policy with the default backoff (2 s, multiplier 2.0).
     *
     * @param maxAttempts attempts including the first
     * @return the policy
     */
    public static RetryPolicy of(int maxAttempts) {
        return new RetryPolicy(maxAttempts, DEFAULT_INITIAL_BACKOFF, DEFAULT_MULTIPLIER);
    }

    /**
     * Computes the backoff before the retry that follows failed attempt {@code attempt}:
     * {@code initialBackoff * multiplier^(attempt-1)}, capped at {@code cap}, then multiplied by {@code scale}.
     *
     * @param attempt the attempt that just failed (1-based)
     * @param cap maximum backoff before scaling
     * @param scale scale factor (0 makes retries immediate)
     * @return the backoff, never negative
     */
    public Duration backoff(int attempt, Duration cap, double scale) {
        double base = initialBackoff.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
        double capped = Math.min(base, cap.toMillis());
        return Duration.ofMillis(Math.max(0L, Math.round(capped * Math.max(0.0, scale))));
    }
}
