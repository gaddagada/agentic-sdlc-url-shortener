package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * Hardening behaviours added after the security review of the engine: denied artifacts never persist, run creation
 * is attributed to the requesting operator.
 */
class OrchestrationEngineHardeningTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void closeEngine() {
        harness.close();
    }

    @Test
    void artifactDeniedByTheExitGateIsRemovedFromHistoryAndTheRunSafeStops() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.changeSet("url-shortener/src/main/java/com/agenticsdlc/shortener/Leak.java",
                        "String awsKey = \"AKIAABCDEFGHIJKLMNOP\";"),
                "implementation with an embedded credential")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "leak", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.stage(WorkflowDefinitions.IMPLEMENT).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.artifacts()).as("the denied change set must not stay in the artifact history")
                .noneMatch(a -> a.kind() == ArtifactKind.CHANGE_SET);
        assertThat(stopped.acceptedVersions()).doesNotContainKey(WorkflowDefinitions.IMPLEMENT);
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.POLICY_DENIED);
            assertThat(e.stageId()).isEqualTo(WorkflowDefinitions.IMPLEMENT);
            assertThat(e.data()).containsEntry("removed", true);
            assertThat(e.message()).doesNotContain("AKIA");
        });
        assertThat(harness.store.load(id)).get().satisfies(persisted ->
                assertThat(persisted.artifacts()).noneMatch(a -> a.kind() == ArtifactKind.CHANGE_SET));
    }

    @Test
    void runCreationIsAttributedToTheRequestingOperator() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "attributed", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO),
                ALICE).id();

        AuditEvent created = harness.events(id).getFirst();
        assertThat(created.type()).isEqualTo(AuditEventType.RUN_CREATED);
        assertThat(created.actor()).isEqualTo(ALICE);
        assertThat(created.data()).containsEntry("requestedBy", "alice");
    }
}
