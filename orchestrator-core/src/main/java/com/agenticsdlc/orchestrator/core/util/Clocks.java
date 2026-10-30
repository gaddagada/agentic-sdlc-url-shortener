package com.agenticsdlc.orchestrator.core.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jspecify.annotations.Nullable;

/**
 * Clock and time arithmetic helpers.
 */
public final class Clocks {

    private Clocks() {
    }

    /**
     * Returns the system UTC clock.
     *
     * @return {@link Clock#systemUTC()}
     */
    public static Clock systemUtc() {
        return Clock.systemUTC();
    }

    /**
     * Returns the number of milliseconds from {@code from} to {@code to}, never negative.
     *
     * @param from start instant
     * @param to end instant
     * @return elapsed milliseconds, {@code 0} when {@code to} is before {@code from}
     */
    public static long millisBetween(Instant from, Instant to) {
        return Math.max(0L, Duration.between(from, to).toMillis());
    }

    /**
     * Returns the number of milliseconds from {@code from} to {@code to}, or {@code null} when either is null.
     *
     * @param from start instant
     * @param to end instant
     * @return elapsed milliseconds or null
     */
    public static @Nullable Long millisBetweenOrNull(@Nullable Instant from, @Nullable Instant to) {
        if (from == null || to == null) {
            return null;
        }
        return millisBetween(from, to);
    }

    /**
     * Returns the current instant of {@code clock} truncated to milliseconds, which keeps persisted
     * timestamps stable across JSON round trips and databases.
     *
     * @param clock the clock
     * @return the current instant with millisecond precision
     */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
