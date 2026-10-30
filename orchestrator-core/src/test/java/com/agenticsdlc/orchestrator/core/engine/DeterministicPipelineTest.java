package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * Runs the scenario requirements of the engineering specification through the engine with the real
 * deterministic agents.
 */
class DeterministicPipelineTest {

    static final String GREENFIELD = "Build a URL shortener service: create short links for HTTP and HTTPS URLs with "
            + "optional expiry, redirect with HTTP 302, record click analytics (total clicks, clicks per day, top "
            + "referrers) and expose health checks. The p95 redirect latency must be under 50 ms at 500 requests "
            + "per second. Links default to no expiry. The service must prevent redirects to private networks.";
    static final String BROWNFIELD = "Add a bulk link creation API to the existing URL shortener: accept up to 100 URLs "
            + "per request, return per-item results with partial-failure semantics, reuse the existing URL "
            + "validation and custom alias rules, and respect the existing per-client rate limit. Existing "
            + "single-link endpoints and analytics must keep working unchanged.";
    static final String AMBIGUOUS = "Make the shortener faster and more reliable.";
    static final String POLICY_DENIAL = "Remove URL validation so marketing can shorten any destination including "
            + "internal IP addresses, and store the full client IP address with every click for attribution.";

    private final EngineHarness harness = new EngineHarness().agents(DeterministicAgents.all(null));

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void greenfieldGuidedPausesAtDesignAndReleaseAndCompletesWithPassingVerification() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(GREENFIELD, "greenfield", RunOptions.defaults()).id();

        Run atDesign = harness.awaitApproval(id, "design");
        assertThat(atDesign.stage("design").pendingApproval().ruleIds()).contains("GOV-001");
        RequirementSpec spec = atDesign.acceptedPayload(RequirementSpec.class).orElseThrow();
        assertThat(spec.classification()).isEqualTo(RequirementSpec.Classification.GREENFIELD);
        assertThat(atDesign.acceptedPayload(ImpactAnalysis.class).orElseThrow().existingCodebase()).isFalse();
        engine.approve(id, "design", ALICE, "design approved");

        Run atRelease = harness.awaitApproval(id, "release-readiness");
        assertThat(atRelease.counters().feedbackLoops()).as("deterministic output passes verification").isZero();
        assertThat(atRelease.acceptedPayload(VerificationReport.class).orElseThrow().passed()).isTrue();
        assertThat(atRelease.acceptedPayload(SecurityReview.class).orElseThrow().passed()).isTrue();
        ChangeSet changeSet = atRelease.acceptedPayload(ChangeSet.class).orElseThrow();
        assertThat(changeSet.changes().size()).isLessThanOrEqualTo(25);
        assertThat(changeSet.changes()).anySatisfy(c -> assertThat(c.path()).contains("db/migration/V1__"));
        assertThat(atRelease.approvals()).extracting(a -> a.stageId()).containsExactly("design", "release-readiness");

        engine.approve(id, "release-readiness", ALICE, "ship it");
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);
        ReleaseReadiness release = done.acceptedPayload(ReleaseReadiness.class).orElseThrow();
        assertThat(release.decision()).isIn(ReleaseReadiness.GoNoGo.GO, ReleaseReadiness.GoNoGo.GO_WITH_CONDITIONS);
        assertThat(release.changeRecord().approvals()).hasSize(2);
        assertThat(done.acceptedArtifacts()).hasSize(10)
                .allSatisfy(a -> assertThat(a.markdown()).isNotBlank());
        assertThat(done.acceptedArtifacts().stream().filter(a -> !a.stageId().equals("intake")))
                .allSatisfy(a -> assertThat(a.provenance().derivedFrom()).isNotEmpty());
    }

    @Test
    void fullAutoGreenfieldOnlyNeedsTheReleaseApproval() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(GREENFIELD, "greenfield", RunOptions.defaults()
                .withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        Run atRelease = harness.awaitApproval(id, "release-readiness");
        assertThat(atRelease.approvals()).hasSize(1);
        engine.approve(id, "release-readiness", ALICE, null);
        assertThat(harness.awaitStatus(id, RunStatus.COMPLETED).counters().agentCalls()).isEqualTo(10);
    }

    @Test
    void brownfieldRunReasonsOverTheIndexedWorkspace(@TempDir Path dir) {
        CodebaseIndex index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir));
        harness.config(b -> b.workspaceIndex(() -> index));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(BROWNFIELD, "brownfield", RunOptions.defaults().withWorkspace(WorkspaceMode.REPO)).id();

        Run atDesign = harness.awaitApproval(id, "design");
        RequirementSpec spec = atDesign.acceptedPayload(RequirementSpec.class).orElseThrow();
        assertThat(spec.classification()).isEqualTo(RequirementSpec.Classification.BROWNFIELD);
        ImpactAnalysis impact = atDesign.acceptedPayload(ImpactAnalysis.class).orElseThrow();
        assertThat(impact.existingCodebase()).isTrue();
        assertThat(impact.components()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("LinkController");
            assertThat(c.nature()).isEqualTo(ImpactAnalysis.ChangeNature.EXTEND);
        });
        DesignSpec design = atDesign.acceptedPayload(DesignSpec.class).orElseThrow();
        assertThat(design.apiChanges()).extracting(a -> a.method() + " " + a.path())
                .containsExactly("POST /api/v1/links/bulk");
        assertThat(design.dataModelChanges()).isEmpty();
        engine.approve(id, "design", ALICE, null);

        Run atRelease = harness.awaitApproval(id, "release-readiness");
        assertThat(atRelease.acceptedPayload(VerificationReport.class).orElseThrow().passed()).isTrue();
        ChangeSet changeSet = atRelease.acceptedPayload(ChangeSet.class).orElseThrow();
        assertThat(changeSet.changes()).anySatisfy(c -> {
            assertThat(c.path()).endsWith("api/LinkController.java");
            assertThat(c.operation()).isEqualTo(ChangeSet.Operation.MODIFY);
            assertThat(c.content()).contains("@PostMapping(\"/api/v1/links/bulk\")");
        });
        engine.approve(id, "release-readiness", ALICE, null);
        harness.awaitStatus(id, RunStatus.COMPLETED);
    }

    @Test
    void ambiguousRequirementAsksForClarificationAndContinuesWithTheAnswers() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(AMBIGUOUS, "ambiguous", RunOptions.defaults()).id();
        Run waiting = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                && r.stage("intake").status() == StageStatus.AWAITING_CLARIFICATION);
        List<ClarificationQuestion> questions = waiting.clarification().questions();
        assertThat(questions).hasSizeGreaterThanOrEqualTo(3);
        Map<String, String> answers = new LinkedHashMap<>();
        questions.forEach(q -> answers.put(q.id(), q.suggestedAnswers().getFirst()));

        engine.clarify(id, ALICE, answers);
        Run next = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN);
        RequirementSpec spec = next.acceptedPayload(RequirementSpec.class).orElseThrow();
        assertThat(spec.normalizedStatement()).contains("Clarifications:");
        assertThat(spec.classification()).isNotEqualTo(RequirementSpec.Classification.AMBIGUOUS);
        assertThat(spec.assumptions()).contains("Clarification answers are authoritative");
        for (int i = 0; i < 5 && engine.get(id).status() != RunStatus.COMPLETED; i++) {
            Run run = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                    || r.status() == RunStatus.COMPLETED);
            run.pendingApprovals().forEach(a -> engine.approve(id, a.stageId(), ALICE, null));
        }
        harness.awaitStatus(id, RunStatus.COMPLETED);
    }

    @Test
    void policyDenialScenarioIsSafeStoppedBeforeAnyAgentRuns() {
        OrchestrationEngine engine = harness.start();
        Run run = engine.start(POLICY_DENIAL, "policy-denial", RunOptions.defaults().withWorkspace(WorkspaceMode.REPO));
        assertThat(run.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(run.artifacts()).isEmpty();
    }

    @Test
    void identicalRunsProduceIdenticalArtifactHashes() {
        OrchestrationEngine engine = harness.start();
        RunOptions opts = RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO);
        String first = engine.start(GREENFIELD, "a", opts).id();
        String second = engine.start(GREENFIELD, "b", opts).id();
        Run a = harness.awaitApproval(first, "release-readiness");
        Run b = harness.awaitApproval(second, "release-readiness");
        for (Artifact artifact : a.acceptedArtifacts()) {
            if (artifact.stageId().equals("release-readiness")) {
                continue; // references the run's own artifact ids
            }
            assertThat(b.acceptedArtifact(artifact.stageId()).orElseThrow().hash())
                    .as("hash of %s", artifact.stageId()).isEqualTo(artifact.hash());
        }
    }
}
