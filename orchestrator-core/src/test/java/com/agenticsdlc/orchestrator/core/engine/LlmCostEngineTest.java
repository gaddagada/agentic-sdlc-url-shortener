package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.LlmTrace;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.metrics.LlmPrice;
import com.agenticsdlc.orchestrator.core.metrics.LlmPriceBook;
import com.agenticsdlc.orchestrator.core.metrics.MetricsCalculator;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * The engine prices every LLM call when it records the call's usage: run counters, provenance, audit data and the
 * metrics recorder carry prompt cache counts and the cost.
 */
class LlmCostEngineTest {

    private static final LlmPriceBook PRICES = LlmPriceBook.builder()
            .price("anthropic", "claude-opus-5", new LlmPrice(5.0, 25.0, 0.5, 6.25)).build();

    /** Records the calls the engine makes through the new default methods. */
    static final class Recorder implements MetricsRecorder {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void stageCompleted(String stageId, StageStatus status, long durationMs) {
        }

        @Override
        public void retry(String stageId) {
        }

        @Override
        public void fallback(String stageId) {
        }

        @Override
        public void rollback(String runId) {
        }

        @Override
        public void runCompleted(long durationMs, long activeMs) {
        }

        @Override
        public void agentCall(String providerId, AgentRole role, long latencyMs, long inputTokens, long outputTokens) {
            calls.add("agentCall " + providerId + " " + role + " " + inputTokens + "/" + outputTokens);
        }

        @Override
        public void agentCall(String providerId, AgentRole role, long latencyMs, long inputTokens, long outputTokens,
                              long cacheReadTokens, long cacheCreationTokens) {
            calls.add("agentCall+cache " + providerId + " " + role + " " + cacheReadTokens + "/" + cacheCreationTokens);
            MetricsRecorder.super.agentCall(providerId, role, latencyMs, inputTokens, outputTokens, cacheReadTokens,
                    cacheCreationTokens);
        }

        @Override
        public void llmCost(String providerId, AgentRole role, @Nullable Long costMicroUsd) {
            calls.add("llmCost " + providerId + " " + role + " " + costMicroUsd);
        }

        @Override
        public void approvalDecision(long decisionMs, boolean approved) {
        }
    }

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    private void llmAgents(String model) {
        harness.agent(new FakeAgent(AgentRole.REQUIREMENTS, (ctx, call) -> new StageResult.Success(
                Payloads.forRole(AgentRole.REQUIREMENTS), "llm", List.of(),
                new LlmTrace("anthropic", model, "h1", 5_000, 200, 3_000, 1_000))));
        harness.agent(new FakeAgent(AgentRole.PLANNING, (ctx, call) -> new StageResult.Success(
                Payloads.forRole(AgentRole.PLANNING), "llm", List.of(),
                new LlmTrace("anthropic", model, "h2", 1_200, 340))));
    }

    private static AuditEvent decision(List<AuditEvent> events, String stageId) {
        return events.stream().filter(e -> e.type() == AuditEventType.AGENT_DECISION
                && stageId.equals(e.stageId())).findFirst().orElseThrow();
    }

    @Test
    void pricesEveryCallIntoCountersProvenanceAuditAndMetrics() {
        Recorder recorder = new Recorder();
        llmAgents("claude-opus-5-5");
        OrchestrationEngine engine = harness.metrics(recorder).config(b -> b.priceBook(PRICES)).start();
        String id = engine.start("Create short links.", "cost", RunOptions.defaults()
                .withAutonomy(AutonomyLevel.FULL_AUTO)).id();

        Run run = harness.awaitApproval(id, "release-readiness");
        // intake: 1000*5 + 200*25 + 3000*0.5 + 1000*6.25 = 17750; decompose: 1200*5 + 340*25 = 14500
        assertThat(run.counters().llmCalls()).isEqualTo(2);
        assertThat(run.counters().llmCacheReadTokens()).isEqualTo(3_000L);
        assertThat(run.counters().llmCacheCreationTokens()).isEqualTo(1_000L);
        assertThat(run.counters().llmCostMicroUsd()).isEqualTo(17_750L + 14_500L);
        assertThat(run.counters().llmUnpricedCalls()).isZero();

        Artifact intake = run.acceptedArtifact("intake").orElseThrow();
        assertThat(intake.provenance().cacheReadTokens()).isEqualTo(3_000L);
        assertThat(intake.provenance().cacheCreationTokens()).isEqualTo(1_000L);
        assertThat(intake.provenance().costMicroUsd()).isEqualTo(17_750L);
        assertThat(run.acceptedArtifact("impact").orElseThrow().provenance().costMicroUsd())
                .as("deterministic agents cost nothing").isZero();

        List<AuditEvent> events = harness.events(id);
        assertThat(decision(events, "intake").data()).containsEntry("provider", "anthropic")
                .containsEntry("model", "claude-opus-5-5").containsEntry("inputTokens", 5_000L)
                .containsEntry("outputTokens", 200L).containsEntry("cacheReadTokens", 3_000L)
                .containsEntry("cacheCreationTokens", 1_000L).containsEntry("costMicroUsd", 17_750L);
        assertThat(decision(events, "impact").data()).doesNotContainKeys("costMicroUsd", "inputTokens");

        assertThat(recorder.calls).contains("agentCall+cache anthropic REQUIREMENTS 3000/1000",
                "agentCall anthropic REQUIREMENTS 5000/200", "llmCost anthropic REQUIREMENTS 17750",
                "llmCost anthropic PLANNING 14500");
        assertThat(recorder.calls).noneMatch(c -> c.startsWith("llmCost deterministic"));

        assertThat(MetricsCalculator.compute(List.of(run), java.time.Clock.systemUTC()).llm().estimatedCostUsd())
                .isEqualTo(0.03225);
    }

    @Test
    void unpricedModelsLeaveTheCostUnknown() {
        Recorder recorder = new Recorder();
        llmAgents("gpt-5");
        OrchestrationEngine engine = harness.metrics(recorder).config(b -> b.priceBook(PRICES)).start();
        String id = engine.start("Create short links.", "unpriced", RunOptions.defaults()
                .withAutonomy(AutonomyLevel.FULL_AUTO)).id();

        Run run = harness.awaitApproval(id, "release-readiness");
        assertThat(run.counters().llmCostMicroUsd()).isNull();
        assertThat(run.counters().llmUnpricedCalls()).isEqualTo(2);
        assertThat(run.acceptedArtifact("intake").orElseThrow().provenance().costMicroUsd()).isNull();
        assertThat(decision(harness.events(id), "intake").data()).containsEntry("cacheReadTokens", 3_000L)
                .doesNotContainKey("costMicroUsd");
        assertThat(recorder.calls).contains("llmCost anthropic REQUIREMENTS null");
    }

    @Test
    void theDefaultPriceBookIsEmpty() {
        assertThat(EngineConfig.defaults().priceBook()).isSameAs(LlmPriceBook.EMPTY);
        llmAgents("claude-opus-5-5");
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links.", "default", RunOptions.defaults()
                .withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        Run run = harness.awaitApproval(id, "release-readiness");
        assertThat(run.counters().llmCostMicroUsd()).isNull();
        assertThat(run.counters().llmUnpricedCalls()).isEqualTo(2);
    }
}
