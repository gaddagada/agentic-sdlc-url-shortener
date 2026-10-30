package com.agenticsdlc.orchestrator.app.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ReliabilityMetricsView;
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.metrics.MetricsCalculator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Reliability metrics computed over every run known to the engine (success rate, retries, fallbacks, rollbacks,
 * MTTR, latency percentiles, human wait, approvals, LLM usage and estimated cost).
 *
 * <p>The computation uses {@link OrchestrationEngine#list()}, the authoritative listing that includes runs evicted from
 * memory and runs of other nodes. It is expensive: every call reads and deserializes every run of the store. The
 * result is therefore reused for {@code sdlc.metrics.cache-ttl} (5 s by default; 0 disables the cache), and concurrent
 * requests for an expired result wait for one computation instead of starting their own.</p>
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Reliability metrics")
public class MetricsController {

    private final OrchestrationEngine engine;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Object lock = new Object();
    private @Nullable ReliabilityMetricsView cached;
    private @Nullable Instant cachedAt;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     * @param clock clock
     * @param properties configuration ({@code sdlc.metrics.cache-ttl})
     */
    public MetricsController(OrchestrationEngine engine, Clock clock, OrchestratorProperties properties) {
        this.engine = engine;
        this.clock = clock;
        this.cacheTtl = properties.metrics().cacheTtl();
    }

    /**
     * Returns the reliability metrics, at most {@code sdlc.metrics.cache-ttl} old.
     *
     * @return the metrics
     */
    @GetMapping("/reliability")
    @Operation(summary = "Get reliability metrics over all runs", operationId = "getReliabilityMetrics",
            description = "Computed over every run of the store (expensive: every run is read); the result is reused "
                    + "for sdlc.metrics.cache-ttl (5 s by default), so it may lag the latest changes by that much.")
    public ReliabilityMetricsView reliability() {
        synchronized (lock) {
            Instant now = clock.instant();
            if (cached != null && cachedAt != null && !cacheTtl.isZero()
                    && now.isBefore(cachedAt.plus(cacheTtl))) {
                return cached;
            }
            ReliabilityMetricsView fresh = ReliabilityMetricsView.from(MetricsCalculator.compute(engine.list(), clock));
            cached = fresh;
            cachedAt = now;
            return fresh;
        }
    }
}
