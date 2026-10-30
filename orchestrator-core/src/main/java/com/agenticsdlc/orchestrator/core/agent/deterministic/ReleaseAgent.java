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
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.ChecklistStatus;
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
 * decision and the change record. Approvals in the change record are left empty: the engine fills them from the
 * authoritative approval records when the run completes.
 *
 * <p>Decision rules: any checklist item with status FAIL (verification, security review, documentation,
 * traceability, rollback plan, a required design approval) gives NO_GO; the agent never waives a failure itself
 * (a human may approve a NO_GO release with a waiver comment, policy CHG-003). Otherwise a skeleton change plan
 * (SKELETON content, or a verification report flagged {@code skeletonOnly}) gives GO_WITH_CONDITIONS with the
 * checklist item "Code complete (skeleton plan)" as N_A and the condition {@value #SKELETON_CONDITION}; MEDIUM
 * security findings become conditions as well; without conditions the decision is GO.</p>
 */
public final class ReleaseAgent implements StageAgent {

    /** Condition attached to the release of a skeleton change plan. */
    public static final String SKELETON_CONDITION = "implementation is a skeleton change plan; complete and build "
            + "before merge";

    /** Checklist item recording that the code is a skeleton change plan. */
    public static final String SKELETON_ITEM = "Code complete (skeleton plan)";

    private static final String VERIFICATION_ITEM = "Verification passed";
    private static final String SECURITY_ITEM = "Security review passed";

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
        checklist.add(new ChecklistItem(VERIFICATION_ITEM, status(verified), verification == null
                ? "no verification report" : verification.structuralChecks().stream().filter(c -> c.passed()).count()
                + "/" + verification.structuralChecks().size() + " checks passed"
                + (verification.notes().isEmpty() ? "" : " (" + String.join("; ", verification.notes()) + ")")));
        boolean secure = security != null && security.passed();
        checklist.add(new ChecklistItem(SECURITY_ITEM, status(secure), security == null
                ? "no security review" : security.findings().size() + " findings"));
        checklist.add(new ChecklistItem("Documentation present", status(docs != null),
                docs != null ? "README, API reference, runbook and changelog generated" : "missing"));
        boolean traced = verification != null && verification.traceability().uncovered().isEmpty();
        checklist.add(new ChecklistItem("Traceability complete", status(traced), verification == null
                ? "unknown" : verification.traceability().requirementsCovered() + "/"
                + verification.traceability().requirementsTotal() + " functional requirements traced"));
        checklist.add(new ChecklistItem("Rollback plan present", status(!changeSet.rollbackPlan().isBlank()),
                changeSet.rollbackPlan().isBlank() ? "missing" : "documented in the change set"));
        boolean approvalRequired = autonomy == AutonomyLevel.STRICT
                || (autonomy == AutonomyLevel.GUIDED && design.risk() != null
                && design.risk().level().atLeast(RiskLevel.MEDIUM));
        checklist.add(new ChecklistItem("Design approval (when required)", !approvalRequired ? ChecklistStatus.N_A
                : status(designApproved), approvalRequired ? "required by autonomy " + autonomy
                + " and design risk" : "not required in " + autonomy + " autonomy"));
        boolean skeleton = changeSet.changes().stream().anyMatch(c -> c.operation() != ChangeSet.Operation.DELETE
                && c.contentKind() == ChangeSet.ContentKind.SKELETON)
                || (verification != null && verification.skeletonOnly());
        if (skeleton) {
            checklist.add(new ChecklistItem(SKELETON_ITEM, ChecklistStatus.N_A, "the change set is a skeleton change "
                    + "plan (TODO markers, failing test stubs); not compilable/complete and not built, so code "
                    + "completeness cannot be assessed"));
        }

        List<String> conditions = new ArrayList<>();
        List<ChecklistItem> failed = checklist.stream().filter(i -> i.status() == ChecklistStatus.FAIL).toList();
        GoNoGo decision;
        if (!failed.isEmpty()) {
            // a failed item is never waived by an agent: only a human can approve a NO_GO release (with a waiver)
            decision = GoNoGo.NO_GO;
            if (!verified) {
                conditions.add("Verification must pass");
            }
            if (!secure) {
                conditions.add("HIGH and CRITICAL security findings must be resolved");
            }
            failed.stream().filter(i -> !i.item().equals(VERIFICATION_ITEM) && !i.item().equals(SECURITY_ITEM))
                    .forEach(i -> conditions.add("Resolve: " + i.item()));
        } else {
            if (security != null) {
                security.findings().stream().filter(f -> f.severity() == Severity.MEDIUM)
                        .forEach(f -> conditions.add("Address: " + f.title()));
            }
            decision = conditions.isEmpty() && !skeleton ? GoNoGo.GO : GoNoGo.GO_WITH_CONDITIONS;
        }
        if (skeleton) {
            conditions.add(SKELETON_CONDITION);
        }
        RiskLevel risk = design.risk() == null ? RiskLevel.LOW : design.risk().level();
        ChangeRecord record = new ChangeRecord(Text.shorten(spec.intent(), 200), risk, changeSet.rollbackPlan(),
                List.of(), artifactIds, "pending human release approval");
        String notes = "Release notes: " + spec.intent() + ". " + spec.functional().size()
                + " functional requirements, " + design.apiChanges().size() + " API changes, "
                + design.dataModelChanges().size() + " data model changes. Decision: " + decision + ".";
        return new ReleaseReadiness(checklist, decision, conditions, record, notes);
    }

    private static ChecklistStatus status(boolean satisfied) {
        return satisfied ? ChecklistStatus.PASS : ChecklistStatus.FAIL;
    }
}
