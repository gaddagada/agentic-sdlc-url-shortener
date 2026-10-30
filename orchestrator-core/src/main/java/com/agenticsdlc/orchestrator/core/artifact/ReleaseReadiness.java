package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code release-readiness} stage: checklist, go/no-go decision and change record. The
 * approvals in the change record are filled by the engine (not the agent) when the run completes, so that
 * approvals are authoritative.
 *
 * @param checklist release checklist
 * @param decision go/no-go decision
 * @param conditions conditions attached to the decision
 * @param changeRecord change record
 * @param releaseNotes release notes
 */
public record ReleaseReadiness(List<ChecklistItem> checklist, GoNoGo decision, List<String> conditions,
                               ChangeRecord changeRecord, String releaseNotes) implements ArtifactPayload {

    /** Allowed checklist statuses. */
    public static final Set<String> CHECKLIST_STATUSES = Set.of("PASS", "FAIL", "WAIVED", "N_A");

    /**
     * Normalizes fields.
     */
    public ReleaseReadiness {
        checklist = Immutables.list(checklist);
        conditions = Immutables.list(conditions);
        releaseNotes = Immutables.text(releaseNotes);
    }

    /** Release decision. */
    public enum GoNoGo {
        /** Release. */
        GO,
        /** Release once the listed conditions are met. */
        GO_WITH_CONDITIONS,
        /** Do not release. */
        NO_GO
    }

    /**
     * A checklist item.
     *
     * @param item the item
     * @param status PASS, FAIL, WAIVED or N_A
     * @param evidence evidence
     */
    public record ChecklistItem(String item, String status, String evidence) {
        /**
         * Normalizes text.
         */
        public ChecklistItem {
            item = Immutables.text(item);
            status = Immutables.text(status);
            evidence = Immutables.text(evidence);
        }
    }

    /**
     * The change record.
     *
     * @param summary change summary
     * @param riskLevel risk level
     * @param rollbackPlan rollback plan
     * @param approvals human approvals (filled by the engine at completion)
     * @param artifactIds ids of the accepted artifacts this release is based on
     * @param policyVerdict policy verdict summary
     */
    public record ChangeRecord(String summary, RiskLevel riskLevel, String rollbackPlan, List<ApprovalRecord> approvals,
                               List<String> artifactIds, String policyVerdict) {
        /**
         * Normalizes fields.
         */
        public ChangeRecord {
            summary = Immutables.text(summary);
            rollbackPlan = Immutables.text(rollbackPlan);
            approvals = Immutables.list(approvals);
            artifactIds = Immutables.list(artifactIds);
            policyVerdict = Immutables.text(policyVerdict);
        }
    }

    /**
     * A recorded human approval.
     *
     * @param stageId stage approved
     * @param approver operator id
     * @param decidedAt ISO-8601 timestamp
     * @param comment approval comment (may be empty)
     */
    public record ApprovalRecord(String stageId, String approver, String decidedAt, String comment) {
        /**
         * Normalizes text.
         */
        public ApprovalRecord {
            stageId = Immutables.text(stageId);
            approver = Immutables.text(approver);
            decidedAt = Immutables.text(decidedAt);
            comment = Immutables.text(comment);
        }
    }

    /**
     * Returns a copy with a different change record.
     *
     * @param record the new change record
     * @return the copy
     */
    public ReleaseReadiness withChangeRecord(ChangeRecord record) {
        return new ReleaseReadiness(checklist, decision, conditions, record, releaseNotes);
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.RELEASE_READINESS;
    }

    @Override
    public void validate() {
        Problems p = new Problems("ReleaseReadiness");
        p.requireNonEmpty(checklist, "checklist");
        p.requireNonNull(decision, "decision");
        p.requireNonNull(changeRecord, "changeRecord");
        for (ChecklistItem item : checklist) {
            p.requireText(item.item(), "checklist item");
            p.require(CHECKLIST_STATUSES.contains(item.status()), "checklist item '" + item.item() + "' status '"
                    + item.status() + "' must be one of " + CHECKLIST_STATUSES);
        }
        if (changeRecord != null) {
            p.requireText(changeRecord.summary(), "changeRecord.summary");
            p.requireNonNull(changeRecord.riskLevel(), "changeRecord.riskLevel");
            p.requireText(changeRecord.rollbackPlan(), "changeRecord.rollbackPlan");
        }
        p.require(decision != GoNoGo.GO_WITH_CONDITIONS || !conditions.isEmpty(),
                "GO_WITH_CONDITIONS requires at least one condition");
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Release readiness").field("Decision", decision);
        md.heading(3, "Checklist").table(List.of("Item", "Status", "Evidence"),
                checklist.stream().map(i -> List.of(i.item(), i.status(), i.evidence())).toList());
        md.heading(3, "Conditions").bullets(conditions);
        if (changeRecord != null) {
            md.heading(3, "Change record")
                    .field("Summary", changeRecord.summary())
                    .field("Risk", changeRecord.riskLevel())
                    .field("Rollback plan", changeRecord.rollbackPlan())
                    .field("Policy verdict", changeRecord.policyVerdict())
                    .field("Artifacts", Markdown.join(changeRecord.artifactIds()));
            md.table(List.of("Stage", "Approver", "Decided at", "Comment"),
                    changeRecord.approvals().stream().map(a -> List.of(a.stageId(), a.approver(), a.decidedAt(),
                            a.comment())).toList());
        }
        md.heading(3, "Release notes").raw(releaseNotes.strip() + "\n");
        return md.render();
    }
}
