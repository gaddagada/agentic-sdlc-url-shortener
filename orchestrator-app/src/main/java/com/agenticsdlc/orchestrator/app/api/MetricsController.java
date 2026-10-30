package com.agenticsdlc.orchestrator.app.api;

import java.time.Clock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ReliabilityMetricsView;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.metrics.MetricsCalculator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Reliability metrics computed over every run known to the engine (success rate, retries, fallbacks, rollbacks,
 * MTTR, latency percentiles, human wait, approvals, LLM usage).
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Reliability metrics")
public class MetricsController {

    private final OrchestrationEngine engine;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     * @param clock clock
     */
    public MetricsController(OrchestrationEngine engine, Clock clock) {
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * Returns the reliability metrics.
     *
     * @return the metrics
     */
    @GetMapping("/reliability")
    @Operation(summary = "Get reliability metrics over all runs", operationId = "getReliabilityMetrics")
    public ReliabilityMetricsView reliability() {
        return ReliabilityMetricsView.from(MetricsCalculator.compute(engine.list(), clock));
    }
}
