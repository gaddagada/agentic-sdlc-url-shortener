package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Clocks;

/**
 * A time interval during which a run waited for a human. Open while {@code end} is null.
 *
 * @param start start time
 * @param end end time, or null while open
 * @param reason the run status that opened it ({@code WAITING_HUMAN} or {@code SAFE_STOPPED})
 */
public record Interval(Instant start, @Nullable Instant end, String reason) {

    /**
     * Returns whether the interval is still open.
     *
     * @return true when open
     */
    public boolean open() {
        return end == null;
    }

    /**
     * Returns the interval length, using {@code now} for an open interval.
     *
     * @param now current time
     * @return length in milliseconds
     */
    public long durationMs(Instant now) {
        return Clocks.millisBetween(start, end == null ? now : end);
    }

    /**
     * Returns a closed copy.
     *
     * @param at end time
     * @return the closed interval
     */
    public Interval close(Instant at) {
        return new Interval(start, at, reason);
    }
}
