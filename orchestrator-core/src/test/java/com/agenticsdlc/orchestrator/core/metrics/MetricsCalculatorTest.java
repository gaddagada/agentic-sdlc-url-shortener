package com.agenticsdlc.orchestrator.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalKind;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class MetricsCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private final Clock clock = Clock.fixed(T0.plus(Duration.ofHours(1)), ZoneOffset.UTC);

    private static Run run(String id, RunStatus status) {
        Run run = Run.create(id, GRAPH, "s", "req", RunOptions.defaults(), T0);
        run.setStatus(status);
        return run;
    }

    private static Run completed(String id, long e2eSeconds, long waitSeconds, int agentCalls, int retries) {
        Run run = run(id, RunStatus.COMPLETED);
        run.markStarted(T0);
        run.openHumanWait(T0.plusSeconds(1), "WAITING_HUMAN");
        run.closeHumanWait(T0.plusSeconds(1 + waitSeconds));
        run.setEndedAt(T0.plusSeconds(e2eSeconds));
        run.updateCounters(c -> {
            RunCounters updated = c;
            for (int i = 0; i < agentCalls; i++) {
                updated = updated.plusAgentCall();
            }
            for (int i = 0; i < retries; i++) {
                updated = updated.plusRetry();
            }
            return updated;
        });
        return run;
    }

    private static Artifact artifact(String runId, long latencyMs, String provider) {
        return new Artifact(runId + "-" + latencyMs, runId, "intake", Payloads.requirementSpec(RiskLevel.LOW).kind(),
                1, 1, "h", Payloads.requirementSpec(RiskLevel.LOW), new Provenance(Map.of(), AgentRole.REQUIREMENTS,
                provider, provider, null, null, 0, 0, latencyMs, 1, 1), T0);
    }

    @Test
    void computesRunStageLatencyRecoveryApprovalAndLlmStatistics() {
        Run a = completed("a", 100, 40, 10, 2);
        Run b = completed("b", 300, 100, 10, 0);
        Run c = completed("c", 200, 0, 12, 1);
        Run aborted = run("d", RunStatus.ABORTED);
        aborted.updateCounters(RunCounters::plusRollback);
        Run stopped = run("e", RunStatus.SAFE_STOPPED);
        Run waiting = run("f", RunStatus.WAITING_HUMAN);
        Run running = run("g", RunStatus.RUNNING);
        running.updateCounters(cn -> cn.plusLlmUsage(1000, 200).plusLlmUsage(500, 100).plusFallback()
                .plusFeedbackLoop().plusReplan().plusRollback());

        a.markFailure(T0.plusSeconds(10), "implement");
        a.markRecoveredIfFirstFailure(T0.plusSeconds(25), "implement");
        b.markFailure(T0.plusSeconds(5), "verify");
        b.markRecoveredIfFirstFailure(T0.plusSeconds(10), "other-stage");
        b.markRecoveredIfFirstFailure(T0.plusSeconds(50), "verify");
        stopped.markFailure(T0.plusSeconds(3), "design");

        a.addArtifact(artifact("a", 100, "deterministic"));
        a.addArtifact(artifact("a", 300, "anthropic"));
        b.addArtifact(artifact("b", 200, "deterministic"));
        b.addArtifact(artifact("b", 0, MetricsCalculator.ENGINE_PROVIDER));

        Approval approved = Approval.request("design", ApprovalKind.EXIT, "r", List.of(), RiskLevel.HIGH, 1, 1, null,
                T0).approve("alice", null, T0.plusSeconds(30));
        Approval rejected = Approval.request("design", ApprovalKind.EXIT, "r", List.of(), RiskLevel.HIGH, 1, 2, null,
                T0).reject("bob", "no", T0.plusSeconds(90));
        Approval pending = Approval.request("release-readiness", ApprovalKind.EXIT, "r", List.of(), RiskLevel.HIGH, 1,
                1, null, T0);
        a.addApproval(approved);
        a.addApproval(rejected);
        a.addApproval(pending);
        a.updateCounters(cn -> cn.plusApprovalRequested().plusApprovalRequested().plusApprovalRequested()
                .plusApprovalApproved().plusApprovalRejected().plusApprovalRevoked());

        ReliabilityMetrics m = MetricsCalculator.compute(List.of(a, b, c, aborted, stopped, waiting, running), clock);

        assertThat(m.runs()).isEqualTo(new ReliabilityMetrics.RunStats(7, 3, 1, 1, 1, 1, 0.75));
        assertThat(m.stages().executions()).isEqualTo(32);
        assertThat(m.stages().retries()).isEqualTo(3);
        assertThat(m.stages().retryRate()).isCloseTo(3.0 / 32, within(1e-9));
        assertThat(m.stages().fallbacks()).isEqualTo(1);
        assertThat(m.stages().rollbacks()).isEqualTo(2);
        assertThat(m.stages().rollbackFrequency()).isCloseTo(2.0 / 7, within(1e-9));
        assertThat(m.stages().feedbackLoops()).isEqualTo(1);
        assertThat(m.stages().replans()).isEqualTo(1);

        assertThat(m.latency().endToEndMsAvg()).isEqualTo(200_000.0);
        assertThat(m.latency().endToEndMsP50()).isEqualTo(200_000L);
        assertThat(m.latency().endToEndMsP95()).isEqualTo(300_000L);
        assertThat(m.latency().humanWaitMsAvg()).isCloseTo(140_000.0 / 3, within(1e-6));
        assertThat(m.latency().activeMsAvg()).isCloseTo((60_000 + 200_000 + 200_000) / 3.0, within(1e-6));
        assertThat(m.latency().agentCallMsAvg()).isEqualTo(200.0);
        assertThat(m.latency().agentCallMsP95()).isEqualTo(300L);

        assertThat(m.recovery()).isEqualTo(new ReliabilityMetrics.RecoveryStats(3, 2, 30_000.0, 1));
        assertThat(m.approvals()).isEqualTo(new ReliabilityMetrics.ApprovalStats(3, 1, 1, 1, 60_000.0));
        assertThat(m.llm()).isEqualTo(new ReliabilityMetrics.LlmStats(2, 1500, 300));
    }

    @Test
    void aggregatesCacheTokensAndKnownCostsAndCountsUnpricedCalls() {
        Run priced = run("p1", RunStatus.COMPLETED);
        priced.updateCounters(c -> c.plusLlmUsage(1000, 100, 600, 200, 4_000L).plusLlmUsage(500, 50, 0, 0, 1_500L));
        Run alsoPriced = run("p2", RunStatus.RUNNING);
        alsoPriced.updateCounters(c -> c.plusLlmUsage(100, 10, 50, 0, 500L));
        Run partlyPriced = run("p3", RunStatus.RUNNING);
        partlyPriced.updateCounters(c -> c.plusLlmUsage(100, 10, 0, 0, 900L).plusLlmUsage(200, 20, 100, 50, null));
        Run deterministic = run("d1", RunStatus.COMPLETED);

        ReliabilityMetrics.LlmStats llm = MetricsCalculator.compute(List.of(priced, alsoPriced, partlyPriced,
                deterministic), clock).llm();

        assertThat(llm.calls()).isEqualTo(5);
        assertThat(llm.inputTokens()).isEqualTo(1900);
        assertThat(llm.outputTokens()).isEqualTo(190);
        assertThat(llm.cacheReadTokens()).isEqualTo(750);
        assertThat(llm.cacheCreationTokens()).isEqualTo(250);
        assertThat(llm.unpricedCalls()).isEqualTo(1);
        assertThat(llm.estimatedCostUsd()).as("known run costs only: 5500 + 500 micro-dollars")
                .isCloseTo(0.006, within(1e-12));

        Run unpriced = run("u1", RunStatus.COMPLETED);
        unpriced.updateCounters(c -> c.plusLlmUsage(10, 1));
        ReliabilityMetrics.LlmStats nothingPriced = MetricsCalculator.compute(List.of(unpriced, deterministic), clock)
                .llm();
        assertThat(nothingPriced.estimatedCostUsd()).as("nothing could be priced").isNull();
        assertThat(nothingPriced.unpricedCalls()).isEqualTo(1);
        assertThat(MetricsCalculator.compute(List.of(deterministic), clock).llm())
                .isEqualTo(new ReliabilityMetrics.LlmStats(0, 0, 0, 0, 0, 0.0, 0))
                .isEqualTo(new ReliabilityMetrics.LlmStats(0, 0, 0));
    }

    @Test
    void emptyInputYieldsZeroes() {
        ReliabilityMetrics m = MetricsCalculator.compute(List.of(), clock);
        assertThat(m.runs().successRate()).isZero();
        assertThat(m.stages().retryRate()).isZero();
        assertThat(m.stages().rollbackFrequency()).isZero();
        assertThat(m.latency().endToEndMsP95()).isZero();
        assertThat(m.recovery().mttrMs()).isZero();
    }

    @Test
    void percentilesUseNearestRank() {
        List<Long> values = List.of(15L, 20L, 35L, 40L, 50L);
        assertThat(MetricsCalculator.percentile(values, 5)).isEqualTo(15L);
        assertThat(MetricsCalculator.percentile(values, 30)).isEqualTo(20L);
        assertThat(MetricsCalculator.percentile(values, 40)).isEqualTo(20L);
        assertThat(MetricsCalculator.percentile(values, 50)).isEqualTo(35L);
        assertThat(MetricsCalculator.percentile(values, 100)).isEqualTo(50L);
        assertThat(MetricsCalculator.percentile(List.of(7L), 95)).isEqualTo(7L);
    }
}
