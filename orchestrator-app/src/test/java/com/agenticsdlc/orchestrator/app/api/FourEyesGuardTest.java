package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalKind;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * {@link FourEyesGuard}: separation of duties on the release-readiness approval.
 */
class FourEyesGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;
    private static final FourEyesGuard ENABLED = FourEyesGuard.of(true);

    @Test
    void theOperatorWhoStartedTheRunCannotApproveItsRelease() {
        Run run = runAwaitingRelease("alice", "bob");

        assertThatThrownBy(() -> ENABLED.checkApproval(run, RELEASE, Actor.human("alice"), null))
                .isInstanceOf(FourEyesViolationException.class).hasMessageContaining("started run");
        assertThatThrownBy(() -> ENABLED.checkApproval(run, RELEASE, Actor.human("ALICE"), null))
                .as("operator ids are compared case-insensitively").isInstanceOf(FourEyesViolationException.class);
    }

    @Test
    void theDesignApproverCannotApproveTheRelease() {
        Run run = runAwaitingRelease("alice", "bob");

        assertThatThrownBy(() -> ENABLED.checkApproval(run, RELEASE, Actor.human("bob"), null))
                .isInstanceOf(FourEyesViolationException.class).hasMessageContaining("approved the design");
    }

    @Test
    void aRevokedDesignApprovalStillCounts() {
        Run run = runAwaitingRelease("alice", "bob");
        Approval granted = run.approvals().getFirst();
        run.replaceApproval(granted.revoke("re-planned", NOW.plusSeconds(60)));

        assertThat(FourEyesGuard.designApprovers(run)).containsExactly("bob");
        assertThatThrownBy(() -> ENABLED.checkApproval(run, RELEASE, Actor.human("bob"), null))
                .isInstanceOf(FourEyesViolationException.class);
    }

    @Test
    void aThirdOperatorMayApproveAndTheDecisionIsBoundToTheCheckedApproval() {
        Run run = runAwaitingRelease("alice", "bob");
        String pendingId = run.stage(RELEASE).pendingApproval().id();

        assertThat(ENABLED.checkApproval(run, RELEASE, Actor.human("carol"), null)).isEqualTo(pendingId);
        assertThat(ENABLED.checkApproval(run, RELEASE, Actor.human("carol"), "reviewed-id"))
                .as("an explicit approval id is kept").isEqualTo("reviewed-id");
    }

    @Test
    void otherStagesRejectionsAndADisabledRuleAreNotRestricted() {
        Run run = runAwaitingRelease("alice", "bob");

        assertThat(ENABLED.checkApproval(run, WorkflowDefinitions.DESIGN, Actor.human("alice"), null)).isNull();
        assertThat(FourEyesGuard.of(false).checkApproval(run, RELEASE, Actor.human("alice"), "x")).isEqualTo("x");
        assertThat(FourEyesGuard.of(false).enabled()).isFalse();
    }

    @Test
    void withoutAPendingReleaseApprovalTheEngineDecides() {
        Run run = run("alice");

        assertThat(ENABLED.checkApproval(run, RELEASE, Actor.human("alice"), null)).isNull();
    }

    private static Run runAwaitingRelease(String createdBy, String designApprover) {
        Run run = run(createdBy);
        run.addApproval(Approval.request(WorkflowDefinitions.DESIGN, ApprovalKind.EXIT, "GOV-001",
                List.of("GOV-001"), RiskLevel.MEDIUM, 1, 1, null, NOW).approve(designApprover, "ok", NOW));
        Approval release = Approval.request(RELEASE, ApprovalKind.EXIT, "GOV-002", List.of("GOV-002"),
                RiskLevel.MEDIUM, 1, 1, null, NOW.plusSeconds(10));
        run.addApproval(release);
        run.stage(RELEASE).setPendingApproval(release);
        return run;
    }

    private static Run run(String createdBy) {
        RunOptions options = new RunOptions(AutonomyLevel.GUIDED, "deterministic", List.of(), null,
                WorkspaceMode.NONE, Map.of(RunLauncher.CREATED_BY_TAG, createdBy));
        return Run.create("run-1", GRAPH, "greenfield", "Build a URL shortener with analytics.", options, NOW);
    }
}
