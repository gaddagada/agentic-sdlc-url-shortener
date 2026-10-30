package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.app.api.dto.ProvenanceView;
import com.agenticsdlc.orchestrator.app.api.dto.ReliabilityMetricsView;
import com.agenticsdlc.orchestrator.app.api.dto.RunView;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.MetricsCalculator;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;

/**
 * The LLM cache and cost figures reach the run view (counters), the artifact provenance view and the reliability
 * metrics view: a priced run has a dollar cost, a run with an unpriced call has none and counts the call, a run
 * without LLM calls costs 0.
 */
class CostAndCacheViewsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final RunViewMapper MAPPER = new RunViewMapper(GRAPH, Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void aPricedRunShowsItsCacheTokensAndDollarCost() {
        Run run = run("priced");
        run.updateCounters(c -> c.plusLlmUsage(5_300, 340, 4_000, 1_000, 12_500L)
                .plusLlmUsage(1_200, 100, 0, 0, 2_500L));

        RunView.CountersView counters = MAPPER.toView(run).counters();

        assertThat(counters.llmCalls()).isEqualTo(2);
        assertThat(counters.llmInputTokens()).isEqualTo(6_500);
        assertThat(counters.llmCacheReadTokens()).isEqualTo(4_000);
        assertThat(counters.llmCacheCreationTokens()).isEqualTo(1_000);
        assertThat(counters.llmCostUsd()).isEqualTo(0.015);
        assertThat(counters.llmUnpricedCalls()).isZero();
    }

    @Test
    void oneUnpricedCallMakesTheCostUnknownAndIsCounted() {
        Run run = run("unpriced");
        run.updateCounters(c -> c.plusLlmUsage(1_000, 100, 0, 0, 2_500L).plusLlmUsage(1_000, 100, 0, 0, null));

        RunView.CountersView counters = MAPPER.toView(run).counters();

        assertThat(counters.llmCostUsd()).isNull();
        assertThat(counters.llmUnpricedCalls()).isEqualTo(1);
        assertThat(MAPPER.toView(run("idle")).counters().llmCostUsd()).as("no LLM call costs 0").isEqualTo(0.0);
    }

    @Test
    void provenanceShowsCacheCountsAndTheCallCost() {
        ProvenanceView priced = ProvenanceView.from(new Provenance(Map.of("intake", 1), AgentRole.ARCHITECTURE,
                "anthropic:claude-opus-5-5", "anthropic", "claude-opus-5-5", "hash", 5_300, 340, 900, 1, 1, 4_000L,
                1_000L, 12_500L));
        ProvenanceView deterministic = ProvenanceView.from(new Provenance(Map.of(), AgentRole.PLANNING,
                "deterministic", "deterministic", null, null, 0, 0, 2, 1, 1));

        assertThat(priced.cacheReadTokens()).isEqualTo(4_000);
        assertThat(priced.cacheCreationTokens()).isEqualTo(1_000);
        assertThat(priced.costMicroUsd()).isEqualTo(12_500L);
        assertThat(deterministic.costMicroUsd()).isZero();
        assertThat(deterministic.cacheReadTokens()).isZero();
    }

    @Test
    void reliabilityMetricsCarryTheCacheTokensTheEstimatedCostAndTheUnpricedCalls() {
        Run priced = run("a");
        priced.updateCounters(c -> c.plusLlmUsage(5_300, 340, 4_000, 1_000, 12_500L));
        Run unpriced = run("b");
        unpriced.updateCounters(c -> c.plusLlmUsage(1_000, 100, 200, 0, null));

        ReliabilityMetricsView.Llm llm = ReliabilityMetricsView.from(MetricsCalculator.compute(List.of(priced,
                unpriced), Clock.fixed(T0, ZoneOffset.UTC))).llm();

        assertThat(llm.calls()).isEqualTo(2);
        assertThat(llm.cacheReadTokens()).isEqualTo(4_200);
        assertThat(llm.cacheCreationTokens()).isEqualTo(1_000);
        assertThat(llm.estimatedCostUsd()).as("a lower bound: the priced run only").isEqualTo(0.0125);
        assertThat(llm.unpricedCalls()).isEqualTo(1);
    }

    private static Run run(String id) {
        return Run.create(id, GRAPH, "custom", "Build a URL shortener with click analytics.", RunOptions.defaults(),
                T0);
    }
}
