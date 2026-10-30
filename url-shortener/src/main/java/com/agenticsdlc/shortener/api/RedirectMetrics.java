package com.agenticsdlc.shortener.api;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * Redirect meters: {@code shortener.redirects} (counter, tag {@code outcome} = {@code redirected|not_found|gone})
 * and {@code shortener.redirect.latency} (timer with p50/p95/p99).
 */
@Component
public class RedirectMetrics {

    /** Redirect outcome, used as the {@code outcome} tag value. */
    public enum Outcome {
        REDIRECTED,
        NOT_FOUND,
        GONE;

        String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private final Timer latency;

    /**
     * Registers the meters.
     *
     * @param registry meter registry
     */
    public RedirectMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("shortener.redirects")
                    .description("Redirect requests by outcome")
                    .tag("outcome", outcome.tagValue())
                    .register(registry));
        }
        this.latency = Timer.builder("shortener.redirect.latency")
                .description("Time to resolve a short code and answer the redirect request")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * Records one redirect request.
     *
     * @param outcome      how it ended
     * @param elapsedNanos handling time
     */
    public void record(Outcome outcome, long elapsedNanos) {
        counters.get(outcome).increment();
        latency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
