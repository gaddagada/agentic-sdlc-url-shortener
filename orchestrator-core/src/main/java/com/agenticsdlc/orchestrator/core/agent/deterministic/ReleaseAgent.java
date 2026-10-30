package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.ChangeRecord;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.ChecklistItem;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.GoNoGo;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;

/**
 * Deterministic release manager ({@code release-readiness}): checklist from upstream artifacts, go/no-go
 * decision (NO_GO when verification or security failed; GO_WITH_CONDITIONS for MEDIUM findings or waived
 * items; else GO) and the change record. Approvals in the change record are left empty: the engine fills
 * them from the authoritative approval records when the run completes.
 */
public final class ReleaseAgent implements StageAgent {

    /**
     * Creates the agent.
     */
    public ReleaseAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.RELEASE;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        boolean designApproved = context.approvals().stream().anyMatch(a -> a.stageId().equals("design")
                || a.stageId().equals(designStage(context)));
        ReleaseReadiness release = assess(context.require(RequirementSpec.class), context.require(DesignSpec.class),
                context.require(ChangeSet.class), context.find(VerificationReport.class).orElse(null),
                context.find(SecurityReview.class).orElse(null), context.find(DocumentationBundle.class).orElse(null),
                context.options().autonomy(), designApproved,
                context.upstream().values().stream().map(Artifact::id).toList());
        return StageResult.success(release, "decision " + release.decision() + " with "
                + release.conditions().size() + " conditions");
    }

    private static String designStage(StageContext context) {
        return context.upstream().entrySet().stream().filter(e -> e.getValue().payload() instanceof DesignSpec)
                .map(java.util.Map.Entry::getKey).findFirst().orElse("design");
    }

    /**
     * Assesses release readiness.
     *
     * @param spec requirement specification
     * @param design design
     * @param changeSet change set
     * @param verification verification report or null
     * @param security security review or null
     * @param docs documentation or null
     * @param autonomy autonomy level
     * @param designApproved whether a design approval exists in the current plan
     * @param artifactIds ids of the upstream artifacts
     * @return the release readiness
     */
    public ReleaseReadiness assess(RequirementSpec spec, DesignSpec design, ChangeSet changeSet,
                                   @Nullable VerificationReport verification, @Nullable SecurityReview security,
                                   @Nullable DocumentationBundle docs, AutonomyLevel autonomy, boolean designApproved,
                                   List<String> artifactIds) {
        List<ChecklistItem> checklist = new ArrayList<>();
        boolean verified = verification != null && verification.passed();
        checklist.add(new ChecklistItem("Verification passed", verified ? "PASS" : "FAIL", verification == null
                ? "no verification report" : verification.structuralChecks().stream().filter(c -> c.passed()).count()
                + "/" + verification.structuralChecks().size() + " checks passed"));
        boolean secure = security != null && security.passed();
        checklist.add(new ChecklistItem("Security review passed", secure ? "PASS" : "FAIL", security == null
                ? "no security review" : security.findings().size() + " findings"));
        checklist.add(new ChecklistItem("Documentation present", docs != null ? "PASS" : "FAIL",
                docs != null ? "README, API reference, runbook and changelog generated" : "missing"));
        boolean traced = verification != null && verification.traceability().uncovered().isEmpty();
        checklist.add(new ChecklistItem("Traceability complete", traced ? "PASS" : "FAIL", verification == null
                ? "unknown" : verification.traceability().requirementsCovered() + "/"
                + verification.traceability().requirementsTotal() + " functional requirements traced"));
        checklist.add(new ChecklistItem("Rollback plan present", changeSet.rollbackPlan().isBlank() ? "FAIL" : "PASS",
                changeSet.rollbackPlan().isBlank() ? "missing" : "documented in the change set"));
        boolean approvalRequired = autonomy == AutonomyLevel.STRICT
                || (autonomy == AutonomyLevel.GUIDED && design.risk() != null
                && design.risk().level().atLeast(RiskLevel.MEDIUM));
        checklist.add(new ChecklistItem("Design approval (when required)", !approvalRequired ? "N_A"
                : designApproved ? "PASS" : "FAIL", approvalRequired ? "required by autonomy " + autonomy
                + " and design risk" : "not required in " + autonomy + " autonomy"));
        boolean skeleton = changeSet.changes().stream()
                .anyMatch(c -> c.contentKind() == ChangeSet.ContentKind.SKELETON);
        if (skeleton) {
            checklist.add(new ChecklistItem("Code complete", "WAIVED",
                    "skeleton code; TODO markers must be completed before production deployment"));
        }

        List<String> conditions = new ArrayList<>();
        GoNoGo decision;
        if (!verified || !secure) {
            decision = GoNoGo.NO_GO;
            if (!verified) {
                conditions.add("Verification must pass");
            }
            if (!secure) {
                conditions.add("HIGH and CRITICAL security findings must be resolved");
            }
        } else {
            if (security != null) {
                security.findings().stream().filter(f -> f.severity() == Severity.MEDIUM)
                        .forEach(f -> conditions.add("Address: " + f.title()));
            }
            checklist.stream().filter(i -> i.status().equals("WAIVED"))
                    .forEach(i -> conditions.add("Waived: " + i.item() + " (" + i.evidence() + ")"));
            checklist.stream().filter(i -> i.status().equals("FAIL"))
                    .forEach(i -> conditions.add("Resolve: " + i.item()));
            decision = conditions.isEmpty() ? GoNoGo.GO : GoNoGo.GO_WITH_CONDITIONS;
        }
        RiskLevel risk = design.risk() == null ? RiskLevel.LOW : design.risk().level();
        ChangeRecord record = new ChangeRecord(Text.shorten(spec.intent(), 200), risk, changeSet.rollbackPlan(),
                List.of(), artifactIds, "pending human release approval");
        String notes = "Release notes: " + spec.intent() + ". " + spec.functional().size()
                + " functional requirements, " + design.apiChanges().size() + " API changes, "
                + design.dataModelChanges().size() + " data model changes. Decision: " + decision + ".";
        return new ReleaseReadiness(checklist, decision, conditions, record, notes);
    }
}
