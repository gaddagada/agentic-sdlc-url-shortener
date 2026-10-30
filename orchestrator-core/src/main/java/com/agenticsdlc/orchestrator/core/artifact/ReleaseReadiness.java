package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code release-readiness} stage: checklist, go/no-go decision and change record. The
 * approvals in the change record are filled by the engine (not the agent) when the run completes, so that
 * approvals are authoritative. A checklist item with status FAIL always requires the decision NO_GO: agents
 * never waive failures themselves (a human may still approve a NO_GO release with a waiver comment).
 *
 * @param checklist release checklist
 * @param decision go/no-go decision
 * @param conditions conditions attached to the decision
 * @param changeRecord change record
 * @param releaseNotes release notes
 */
public record ReleaseReadiness(List<ChecklistItem> checklist, GoNoGo decision, List<String> conditions,
                               ChangeRecord changeRecord, String releaseNotes) implements ArtifactPayload {

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

    /** Status of a checklist item. */
    public enum ChecklistStatus {
        /** The item is satisfied. */
        PASS,
        /** The item is not satisfied. */
        FAIL,
        /** The item is not satisfied but explicitly waived (becomes a release condition). */
        WAIVED,
        /** The item does not apply. */
        N_A
    }

    /**
     * A checklist item.
     *
     * @param item the item
     * @param status PASS, FAIL, WAIVED or N_A
     * @param evidence evidence
     */
    public record ChecklistItem(String item, ChecklistStatus status, String evidence) {
        /**
         * Normalizes text.
         */
        public ChecklistItem {
            item = Immutables.text(item);
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
     * @param signature the engine's signature over the finalized record (set when the run completes and a signer is
     *                  configured), or null: unsigned, not finalized yet, or written before signatures existed. Agents
     *                  never sign; a signature an agent puts here is discarded
     */
    public record ChangeRecord(String summary, RiskLevel riskLevel, String rollbackPlan, List<ApprovalRecord> approvals,
                               List<String> artifactIds, String policyVerdict, @Nullable Signature signature) {
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

        /**
         * Creates an unsigned change record (the constructor before signatures existed).
         *
         * @param summary change summary
         * @param riskLevel risk level
         * @param rollbackPlan rollback plan
         * @param approvals human approvals
         * @param artifactIds ids of the accepted artifacts this release is based on
         * @param policyVerdict policy verdict summary
         */
        public ChangeRecord(String summary, RiskLevel riskLevel, String rollbackPlan, List<ApprovalRecord> approvals,
                            List<String> artifactIds, String policyVerdict) {
            this(summary, riskLevel, rollbackPlan, approvals, artifactIds, policyVerdict, null);
        }

        /**
         * Returns a copy with a different signature.
         *
         * @param newSignature the signature, or null for an unsigned copy
         * @return the copy
         */
        public ChangeRecord withSignature(@Nullable Signature newSignature) {
            return new ChangeRecord(summary, riskLevel, rollbackPlan, approvals, artifactIds, policyVerdict,
                    newSignature);
        }
    }

    /**
     * A signature over a finalized change record: {@code value} signs the canonical JSON of the record without its
     * {@code signature} member (see {@code ChangeRecordSigner.canonicalPayload} in the audit package).
     * {@code signedAt} is informational and not covered by the signature.
     *
     * @param algorithm signature algorithm, for example {@code HmacSHA256}
     * @param keyId identifier of the signing key (never the key itself)
     * @param value the signature (lowercase hex for HMAC)
     * @param signedAt ISO-8601 UTC time of signing (a string, like {@link ApprovalRecord#decidedAt()}, so that the
     *                 payload stays expressible as a structured-output JSON schema)
     */
    public record Signature(String algorithm, String keyId, String value, String signedAt) {
        /**
         * Normalizes text.
         */
        public Signature {
            algorithm = Immutables.text(algorithm);
            keyId = Immutables.text(keyId);
            value = Immutables.text(value);
            signedAt = Immutables.text(signedAt);
        }
    }

    /**
     * A recorded human approval (engine-authored; approvals an agent puts here are discarded).
     *
     * @param stageId stage approved
     * @param approver operator id
     * @param decidedAt ISO-8601 timestamp
     * @param comment approval comment (may be empty)
     * @param artifactHash hash of the artifact the operator approved (exit gates), empty for entry gates
     */
    public record ApprovalRecord(String stageId, String approver, String decidedAt, String comment,
                                 String artifactHash) {
        /**
         * Normalizes text.
         */
        public ApprovalRecord {
            stageId = Immutables.text(stageId);
            approver = Immutables.text(approver);
            decidedAt = Immutables.text(decidedAt);
            comment = Immutables.text(comment);
            artifactHash = Immutables.text(artifactHash);
        }

        /**
         * Creates a record without an artifact hash (entry gates).
         *
         * @param stageId stage approved
         * @param approver operator id
         * @param decidedAt ISO-8601 timestamp
         * @param comment approval comment (may be empty)
         */
        public ApprovalRecord(String stageId, String approver, String decidedAt, String comment) {
            this(stageId, approver, decidedAt, comment, "");
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
            p.requireNonNull(item.status(), "checklist item '" + item.item() + "' status");
        }
        if (changeRecord != null) {
            p.requireText(changeRecord.summary(), "changeRecord.summary");
            p.requireNonNull(changeRecord.riskLevel(), "changeRecord.riskLevel");
            p.requireText(changeRecord.rollbackPlan(), "changeRecord.rollbackPlan");
        }
        p.require(decision != GoNoGo.GO_WITH_CONDITIONS || !conditions.isEmpty(),
                "GO_WITH_CONDITIONS requires at least one condition");
        List<String> failed = checklist.stream().filter(i -> i.status() == ChecklistStatus.FAIL)
                .map(ChecklistItem::item).toList();
        p.require(failed.isEmpty() || decision == null || decision == GoNoGo.NO_GO, "decision must be NO_GO when a "
                + "checklist item failed (" + String.join(", ", failed) + "); failures are never self-waived");
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Release readiness").field("Decision", decision);
        md.heading(3, "Checklist").table(List.of("Item", "Status", "Evidence"),
                checklist.stream().map(i -> List.of(i.item(), String.valueOf(i.status()), i.evidence())).toList());
        md.heading(3, "Conditions").bullets(conditions);
        if (changeRecord != null) {
            Signature signature = changeRecord.signature();
            md.heading(3, "Change record")
                    .field("Summary", changeRecord.summary())
                    .field("Risk", changeRecord.riskLevel())
                    .field("Rollback plan", changeRecord.rollbackPlan())
                    .field("Policy verdict", changeRecord.policyVerdict())
                    .field("Artifacts", Markdown.join(changeRecord.artifactIds()));
            if (signature != null) {
                md.field("Signature", signature.algorithm() + " (key " + signature.keyId() + ", signed "
                        + signature.signedAt() + ")");
            }
            md.table(List.of("Stage", "Approver", "Decided at", "Comment", "Artifact hash"),
                    changeRecord.approvals().stream().map(a -> List.of(a.stageId(), a.approver(), a.decidedAt(),
                            a.comment(), a.artifactHash().isEmpty() ? "-" : a.artifactHash())).toList());
        }
        md.heading(3, "Release notes").raw(releaseNotes.strip() + "\n");
        return md.render();
    }
}
