package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
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

    @Test
    void skeletonChangePlansAreVerifiedStructurallyEvenWhenAnLlmProviderIsSelected() {
        AtomicInteger llmVerifierCalls = new AtomicInteger();
        StageAgent llmImplementer = new StageAgent() {
            @Override
            public AgentRole role() {
                return AgentRole.IMPLEMENTATION;
            }

            @Override
            public String id() {
                return "fake-llm:impl";
            }

            @Override
            public StageResult execute(StageContext context) {
                return StageResult.success(skeletonPlan(), "fell back to a skeleton plan");
            }
        };
        StageAgent llmVerifier = new StageAgent() {
            @Override
            public AgentRole role() {
                return AgentRole.VERIFICATION;
            }

            @Override
            public String id() {
                return "fake-llm:verifier";
            }

            @Override
            public StageResult execute(StageContext context) throws Exception {
                llmVerifierCalls.incrementAndGet();
                throw StageFailure.fatal("an LLM verifier must not review a skeleton plan");
            }
        };
        harness.provider("fake-llm", llmImplementer, llmVerifier);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "skeleton-verify",
                RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO).withProvider("fake-llm")).id();

        Run atRelease = harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);
        assertThat(llmVerifierCalls.get()).as("the LLM verifier is bypassed for skeleton plans").isZero();
        assertThat(atRelease.stage(WorkflowDefinitions.VERIFY).activeAgent()).isEqualTo(AgentRegistry.DETERMINISTIC);
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.STAGE_STARTED);
            assertThat(e.stageId()).isEqualTo(WorkflowDefinitions.VERIFY);
            assertThat(String.valueOf(e.data().get("agentSelection"))).contains("structural verification");
        });
    }

    private static ChangeSet skeletonPlan() {
        return new ChangeSet("HEAD", List.of(
                new ChangeSet.FileChange("url-shortener/src/main/java/com/agenticsdlc/shortener/api/BulkLinkController.java",
                        ChangeSet.Operation.CREATE, "java", "bulk endpoint skeleton", ChangeSet.ContentKind.SKELETON,
                        "public class BulkLinkController { /* TODO implement */ }", List.of("T1")),
                new ChangeSet.FileChange("url-shortener/src/test/java/com/agenticsdlc/shortener/api/BulkLinkControllerTest.java",
                        ChangeSet.Operation.CREATE, "java", "bulk endpoint test skeleton", ChangeSet.ContentKind.SKELETON,
                        "class BulkLinkControllerTest { void t() { org.junit.jupiter.api.Assertions.fail(\"TODO\"); } }",
                        List.of("T1"))),
                "./mvnw -B -ntp -q verify", List.of("skeleton implementation; not compilable/complete"),
                "Revert the commit", List.of("T1"));
    }
}
