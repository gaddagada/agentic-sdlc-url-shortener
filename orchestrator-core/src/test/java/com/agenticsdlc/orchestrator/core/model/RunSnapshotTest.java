package com.agenticsdlc.orchestrator.core.model;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

import tools.jackson.databind.node.ObjectNode;

class RunSnapshotTest {

    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private final JsonCodec codec = JsonCodec.standard();
    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    private void assertRoundTrip(Run run) {
        RunSnapshot snapshot = run.toSnapshot();
        String json = codec.toJson(snapshot);
        RunSnapshot parsed = codec.fromJson(json, RunSnapshot.class);
        assertThat(parsed).isEqualTo(snapshot);
        Run restored = Run.fromSnapshot(parsed, GRAPH);
        assertThat(codec.toJson(restored.toSnapshot())).isEqualTo(json);
        assertThat(restored.artifacts()).hasSameSizeAs(run.artifacts());
        for (int i = 0; i < run.artifacts().size(); i++) {
            Artifact original = run.artifacts().get(i);
            Artifact copy = restored.artifacts().get(i);
            assertThat(copy.payload()).isEqualTo(original.payload());
            assertThat(codec.hash(copy.payload())).isEqualTo(original.hash());
            assertThat(copy.provenance()).isEqualTo(original.provenance());
        }
        assertThat(restored.stages()).extracting(StageExecution::stageId)
                .containsExactlyElementsOf(GRAPH.topologicalOrder());
        assertThat(restored.currentRisk()).isEqualTo(run.currentRisk());
    }

    @Test
    void completedRunWithApprovalsCompensationsAndReplanRoundTrips() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.forRole(AgentRole.IMPLEMENTATION), "impl",
                List.of(new CompensationRequest(EngineHarness.TEST_UNDO, "target-" + call, "undo")))));
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links.", "snapshot", RunOptions.defaults()
                .withFaults(List.of(new FaultSpec("decompose", 1, FaultKind.TRANSIENT)))
                .withBudget(RunBudget.defaults().withMaxAgentCalls(60))).id();
        harness.awaitApproval(id, "design");
        engine.replan(id, ALICE, "Create short links with aliases.");
        harness.awaitApproval(id, "design");
        engine.approve(id, "design", ALICE, "ok");
        harness.awaitApproval(id, "release-readiness");
        engine.approve(id, "release-readiness", ALICE, "ship");
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        assertThat(done.approvals()).extracting(Approval::status).contains(ApprovalStatus.REVOKED,
                ApprovalStatus.APPROVED);
        assertThat(done.compensations()).isNotEmpty();
        assertThat(done.checkpoints()).isNotEmpty();
        assertThat(done.policyFindings()).isNotEmpty();
        assertThat(done.injectedFaults("decompose")).isEqualTo(1);
        assertThat(done.firstFailureAt()).isNotNull();
        assertRoundTrip(done);
    }

    @Test
    void inFlightStateRoundTrips() {
        harness.agent(new FakeAgent(AgentRole.REQUIREMENTS, (ctx, call) -> ctx.hasClarificationAnswers()
                ? StageResult.success(Payloads.forRole(AgentRole.REQUIREMENTS), "ok")
                : StageResult.needsClarification(List.of(new ClarificationQuestion("q1", "What?", "why",
                List.of("this"))))));
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Make it better.", "pending", RunOptions.defaults()).id();
        Run waiting = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN);
        assertThat(waiting.clarification()).isNotNull();
        assertRoundTrip(waiting);
        engine.clarify(id, ALICE, Map.of("q1", "this"));
        Run atDesign = harness.awaitApproval(id, "design");
        assertThat(atDesign.stage("design").pendingApproval()).isNotNull();
        assertRoundTrip(atDesign);
        Run restored = Run.fromSnapshot(atDesign.toSnapshot(), GRAPH);
        assertThat(restored.stage("design").pendingApproval()).isEqualTo(atDesign.stage("design").pendingApproval());
        assertThat(restored.clarification().answers()).containsEntry("q1", "this");
        assertThat(restored.humanWaitIntervals()).isEqualTo(atDesign.humanWaitIntervals());
    }

    @Test
    void snapshotsOfOtherWorkflowsOrUnknownStagesAreRejected() {
        Run run = Run.create("r", GRAPH, "s", "req", RunOptions.defaults(), Instant.parse("2026-01-01T00:00:00Z"));
        RunSnapshot snapshot = run.toSnapshot();
        WorkflowGraph other = new WorkflowGraph(new WorkflowDefinition("other", 1,
                WorkflowDefinitions.defaultSdlc().stages()));
        assertThatThrownBy(() -> Run.fromSnapshot(snapshot, other)).isInstanceOf(IllegalArgumentException.class);
        WorkflowGraph smaller = new WorkflowGraph(new WorkflowDefinition("sdlc-default", 2,
                WorkflowDefinitions.defaultSdlc().stages().subList(0, 3)));
        assertThatThrownBy(() -> Run.fromSnapshot(snapshot, smaller)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown stage");
    }

    @Test
    void snapshotsWrittenBeforeTheVersionCounterStillReserveFreshVersions() {
        Run run = Run.create("r", GRAPH, "s", "req", RunOptions.defaults(), Instant.parse("2026-01-01T00:00:00Z"));
        run.addArtifact(Contexts.artifact(Payloads.design(RiskLevel.LOW)));
        ObjectNode legacy = (ObjectNode) codec.readTree(codec.toJson(run.toSnapshot()));
        assertThat(legacy.has("lastArtifactVersions")).isTrue();
        legacy.remove("lastArtifactVersions");

        Run restored = Run.fromSnapshot(codec.fromJson(legacy.toString(), RunSnapshot.class), GRAPH);
        assertThat(restored.lastArtifactVersions()).isEmpty();
        assertThat(restored.nextArtifactVersion("design")).isEqualTo(2);
        assertThat(restored.nextArtifactVersion("design")).isEqualTo(3);
        assertThat(restored.lastArtifactVersions()).containsEntry("design", 3);
        assertThat(restored.copy().lastArtifactVersions()).containsEntry("design", 3);
    }

    @Test
    void copiesAreDeepForMutableState() {
        Run run = Run.create("r", GRAPH, "s", "req", RunOptions.defaults(), Instant.parse("2026-01-01T00:00:00Z"));
        Run copy = run.copy();
        copy.stage("intake").setStatus(StageStatus.RUNNING);
        copy.stage("intake").addFeedback("x");
        copy.updateCounters(RunCounters::plusRetry);
        assertThat(run.stage("intake").status()).isEqualTo(StageStatus.PENDING);
        assertThat(run.stage("intake").feedback()).isEmpty();
        assertThat(run.counters()).isEqualTo(RunCounters.ZERO);
    }
}
