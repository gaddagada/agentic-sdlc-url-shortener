package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.ChecklistItem;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.ChecklistStatus;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.GoNoGo;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

class ReleaseAgentTest {

    private final RequirementSpec spec = new RequirementsAgent().analyze("Build a URL shortener service: create short "
            + "links and redirect with HTTP 302. The service must prevent redirects to private networks.", List.of(),
            Map.of(), null, WorkspaceMode.NONE, 0.5);
    private final ImpactAnalysis impact = new ImpactAnalyzer().proposeGreenfield(spec);
    private final DesignSpec design = new ArchitectureAgent().design(spec, impact);
    private final ChangeSet changeSet = new ImplementationAgent().implement(design, spec, impact, null, List.of());
    private final TestPlan testPlan = new TestDesignAgent().plan(spec, design);
    private final VerificationReport verification = new VerificationAgent().verify(changeSet, design, testPlan, spec,
            null);
    private final SecurityReview security = new SecurityReviewAgent().review(design, changeSet);
    private final DocumentationBundle docs = new DocumentationAgent().document(spec, design, changeSet);
    private final ReleaseAgent agent = new ReleaseAgent();

    /** The same change set with complete (FULL) content instead of skeletons. */
    private ChangeSet complete() {
        return new ChangeSet(changeSet.baseRevision(), changeSet.changes().stream()
                .map(c -> new ChangeSet.FileChange(c.path(), c.operation(), c.language(), c.summary(),
                        ChangeSet.ContentKind.FULL, c.content(), c.traces())).toList(), changeSet.buildCommand(),
                List.of(), changeSet.rollbackPlan(), changeSet.traces());
    }

    private static VerificationReport passing(boolean skeletonOnly) {
        return new VerificationReport(List.of(com.agenticsdlc.orchestrator.core.artifact.Check.pass("x", "ok")),
                new VerificationReport.Traceability(1, 1, List.of()), null, true, List.of(), skeletonOnly, List.of());
    }

    @Test
    void passingInputsWithSkeletonCodeGiveGoWithConditionsAndNeverAWaiver() {
        assertThat(verification.passed()).isTrue();
        assertThat(verification.skeletonOnly()).isTrue();
        ReleaseReadiness release = agent.assess(spec, design, changeSet, verification, security, docs,
                AutonomyLevel.GUIDED, true, List.of("a1", "a2"));
        release.validate();
        assertThat(release.decision()).isEqualTo(GoNoGo.GO_WITH_CONDITIONS);
        assertThat(release.conditions()).contains(ReleaseAgent.SKELETON_CONDITION)
                .contains("implementation is a skeleton change plan; complete and build before merge");
        assertThat(release.checklist()).extracting(ChecklistItem::status).doesNotContain(ChecklistStatus.WAIVED,
                ChecklistStatus.FAIL);
        assertThat(release.checklist()).filteredOn(i -> i.item().equals(ReleaseAgent.SKELETON_ITEM)).singleElement()
                .satisfies(i -> {
                    assertThat(i.status()).isEqualTo(ChecklistStatus.N_A);
                    assertThat(i.evidence()).contains("skeleton change plan").contains("not compilable/complete");
                });
        assertThat(release.checklist()).extracting(ChecklistItem::item)
                .contains("Verification passed", "Security review passed", "Documentation present",
                        "Traceability complete", "Rollback plan present", "Design approval (when required)");
        assertThat(release.checklist()).filteredOn(i -> i.item().equals("Verification passed")).singleElement()
                .satisfies(i -> assertThat(i.evidence()).contains(VerificationReport.NO_BUILD_NOTE));
        assertThat(release.checklist()).filteredOn(i -> i.item().startsWith("Design approval"))
                .singleElement().satisfies(i -> assertThat(i.status()).isEqualTo(ChecklistStatus.PASS));
        assertThat(release.changeRecord().approvals()).as("filled by the engine at completion").isEmpty();
        assertThat(release.changeRecord().artifactIds()).containsExactly("a1", "a2");
        assertThat(release.changeRecord().riskLevel()).isEqualTo(design.risk().level());
    }

    @Test
    void aSkeletonOnlyVerificationAloneAlsoGivesGoWithConditions() {
        ChangeSet full = complete();
        ReleaseReadiness release = agent.assess(spec, design, full, passing(true), security, docs,
                AutonomyLevel.FULL_AUTO, false, List.of());
        release.validate();
        assertThat(release.decision()).isEqualTo(GoNoGo.GO_WITH_CONDITIONS);
        assertThat(release.conditions()).contains(ReleaseAgent.SKELETON_CONDITION);
    }

    @Test
    void completeVerifiedCodeWithoutFindingsGivesGo() {
        SecurityReview clean = new SecurityReview(security.threats(), security.checks(), List.of(), List.of(), true);
        ReleaseReadiness release = agent.assess(spec, design, complete(), passing(false), clean, docs,
                AutonomyLevel.FULL_AUTO, false, List.of());
        release.validate();
        assertThat(release.decision()).isEqualTo(GoNoGo.GO);
        assertThat(release.conditions()).isEmpty();
        assertThat(release.checklist()).extracting(ChecklistItem::item).doesNotContain(ReleaseAgent.SKELETON_ITEM);
    }

    @Test
    void failedVerificationOrSecurityGivesNoGo() {
        VerificationReport failed = new VerificationReport(verification.structuralChecks(),
                new VerificationReport.Traceability(2, 1, List.of("FR-2")), null, false, List.of("Add test cases"));
        ReleaseReadiness release = agent.assess(spec, design, changeSet, failed, security, docs, AutonomyLevel.GUIDED,
                true, List.of());
        release.validate();
        assertThat(release.decision()).isEqualTo(GoNoGo.NO_GO);
        assertThat(release.conditions()).contains("Verification must pass", "Resolve: Traceability complete");

        SecurityReview insecure = new SecurityReview(security.threats(), security.checks(),
                List.of(new SecurityReview.Finding(Severity.HIGH, "No SSRF guard", "add one")), List.of(), false);
        ReleaseReadiness insecureRelease = agent.assess(spec, design, changeSet, verification, insecure, docs,
                AutonomyLevel.FULL_AUTO, false, List.of());
        assertThat(insecureRelease.decision()).isEqualTo(GoNoGo.NO_GO);
        assertThat(insecureRelease.conditions()).contains("HIGH and CRITICAL security findings must be resolved");
        assertThat(agent.assess(spec, design, changeSet, null, null, null, AutonomyLevel.FULL_AUTO, false, List.of())
                .decision()).isEqualTo(GoNoGo.NO_GO);
    }

    @Test
    void anyFailedChecklistItemGivesNoGoAndIsNeverSelfWaived() {
        ReleaseReadiness undocumented = agent.assess(spec, design, changeSet, verification, security, null,
                AutonomyLevel.FULL_AUTO, false, List.of());
        undocumented.validate();
        assertThat(undocumented.decision()).as("verification and security passed").isEqualTo(GoNoGo.NO_GO);
        assertThat(undocumented.conditions()).contains("Resolve: Documentation present");

        ChangeSet noRollback = new ChangeSet(changeSet.baseRevision(), changeSet.changes(), changeSet.buildCommand(),
                changeSet.risks(), "", changeSet.traces());
        assertThat(agent.assess(spec, design, noRollback, verification, security, docs, AutonomyLevel.FULL_AUTO,
                false, List.of()).decision()).isEqualTo(GoNoGo.NO_GO);
        for (ReleaseReadiness release : List.of(undocumented, agent.assess(spec, design, noRollback, verification,
                security, docs, AutonomyLevel.FULL_AUTO, false, List.of()))) {
            assertThat(release.checklist()).extracting(ChecklistItem::status).doesNotContain(ChecklistStatus.WAIVED);
        }
    }

    @Test
    void mediumFindingsBecomeConditionsAndAMissingRequiredDesignApprovalGivesNoGo() {
        SecurityReview medium = new SecurityReview(security.threats(), security.checks(),
                List.of(new SecurityReview.Finding(Severity.MEDIUM, "Rate limit missing", "add it")), List.of(), true);
        ReleaseReadiness approved = agent.assess(spec, design, changeSet, verification, medium, docs,
                AutonomyLevel.GUIDED, true, List.of());
        assertThat(approved.decision()).isEqualTo(GoNoGo.GO_WITH_CONDITIONS);
        assertThat(approved.conditions()).contains("Address: Rate limit missing", ReleaseAgent.SKELETON_CONDITION);

        assertThat(design.risk().level().atLeast(RiskLevel.MEDIUM)).isTrue();
        ReleaseReadiness unapproved = agent.assess(spec, design, changeSet, verification, medium, docs,
                AutonomyLevel.GUIDED, false, List.of());
        unapproved.validate();
        assertThat(unapproved.decision()).isEqualTo(GoNoGo.NO_GO);
        assertThat(unapproved.conditions()).contains("Resolve: Design approval (when required)");
        assertThat(agent.assess(spec, design, changeSet, verification, security, docs, AutonomyLevel.FULL_AUTO, false,
                List.of()).checklist()).filteredOn(i -> i.item().startsWith("Design approval"))
                .singleElement().satisfies(i -> assertThat(i.status()).isEqualTo(ChecklistStatus.N_A));
    }

    @Test
    void releaseValidationRejectsAFailedItemWithoutNoGo() {
        ReleaseReadiness selfWaived = new ReleaseReadiness(List.of(new ChecklistItem("Verification passed",
                ChecklistStatus.FAIL, "2 checks failed")), GoNoGo.GO_WITH_CONDITIONS, List.of("fix it later"),
                new ReleaseReadiness.ChangeRecord("summary", RiskLevel.LOW, "revert", List.of(), List.of(), "pending"),
                "notes");
        assertThatThrownBy(selfWaived::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).singleElement().asString().contains("must be NO_GO")
                        .contains("Verification passed"));
    }
}
