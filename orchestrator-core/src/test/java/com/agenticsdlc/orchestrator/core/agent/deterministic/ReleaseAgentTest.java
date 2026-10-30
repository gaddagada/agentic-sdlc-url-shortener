package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.GoNoGo;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
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

    @Test
    void passingInputsWithSkeletonCodeGiveGoWithConditions() {
        ReleaseReadiness release = agent.assess(spec, design, changeSet, verification, security, docs,
                AutonomyLevel.GUIDED, true, List.of("a1", "a2"));
        release.validate();
        assertThat(release.decision()).isEqualTo(GoNoGo.GO_WITH_CONDITIONS);
        assertThat(release.conditions()).anySatisfy(c -> assertThat(c).startsWith("Waived: Code complete"));
        assertThat(release.checklist()).extracting(ReleaseReadiness.ChecklistItem::item)
                .contains("Verification passed", "Security review passed", "Documentation present",
                        "Traceability complete", "Rollback plan present", "Design approval (when required)");
        assertThat(release.checklist()).filteredOn(i -> i.item().startsWith("Design approval"))
                .singleElement().satisfies(i -> assertThat(i.status()).isEqualTo("PASS"));
        assertThat(release.changeRecord().approvals()).as("filled by the engine at completion").isEmpty();
        assertThat(release.changeRecord().artifactIds()).containsExactly("a1", "a2");
        assertThat(release.changeRecord().riskLevel()).isEqualTo(design.risk().level());
    }

    @Test
    void failedVerificationOrSecurityGivesNoGo() {
        VerificationReport failed = new VerificationReport(verification.structuralChecks(),
                new VerificationReport.Traceability(2, 1, List.of("FR-2")), null, false, List.of("Add test cases"));
        ReleaseReadiness release = agent.assess(spec, design, changeSet, failed, security, docs, AutonomyLevel.GUIDED,
                true, List.of());
        assertThat(release.decision()).isEqualTo(GoNoGo.NO_GO);
        assertThat(release.conditions()).contains("Verification must pass");

        SecurityReview insecure = new SecurityReview(security.threats(), security.checks(),
                List.of(new SecurityReview.Finding(Severity.HIGH, "No SSRF guard", "add one")), List.of(), false);
        assertThat(agent.assess(spec, design, changeSet, verification, insecure, docs, AutonomyLevel.FULL_AUTO, false,
                List.of()).decision()).isEqualTo(GoNoGo.NO_GO);
        assertThat(agent.assess(spec, design, changeSet, null, null, null, AutonomyLevel.FULL_AUTO, false, List.of())
                .decision()).isEqualTo(GoNoGo.NO_GO);
    }

    @Test
    void mediumFindingsBecomeConditionsAndMissingRequiredDesignApprovalFails() {
        SecurityReview medium = new SecurityReview(security.threats(), security.checks(),
                List.of(new SecurityReview.Finding(Severity.MEDIUM, "Rate limit missing", "add it")), List.of(), true);
        ReleaseReadiness release = agent.assess(spec, design, changeSet, verification, medium, docs,
                AutonomyLevel.GUIDED, false, List.of());
        assertThat(release.conditions()).contains("Address: Rate limit missing", "Resolve: Design approval (when "
                + "required)");
        assertThat(release.decision()).isEqualTo(GoNoGo.GO_WITH_CONDITIONS);
        assertThat(agent.assess(spec, design, changeSet, verification, security, docs, AutonomyLevel.FULL_AUTO, false,
                List.of()).checklist()).filteredOn(i -> i.item().startsWith("Design approval"))
                .singleElement().satisfies(i -> assertThat(i.status()).isEqualTo("N_A"));
    }
}
