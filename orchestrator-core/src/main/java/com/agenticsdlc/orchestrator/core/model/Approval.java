package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.util.Ids;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A human approval request at a stage gate and its decision. Immutable: decisions produce new instances.
 *
 * @param id approval id
 * @param stageId gated stage
 * @param kind entry or exit gate
 * @param status status
 * @param reason why approval is required (rule ids and gate reasons)
 * @param ruleIds policy rule ids that required approval
 * @param riskLevel run risk level when the approval was requested
 * @param planVersion plan version when requested
 * @param artifactVersion artifact version under review (exit gates)
 * @param previousAcceptedVersion version accepted before the one under review (restored on rework)
 * @param requestedAt request time
 * @param decidedAt decision time
 * @param decidedBy deciding operator id
 * @param comment approval comment or rejection reason
 * @param revokedAt revocation time
 * @param revokedReason revocation reason
 */
public record Approval(
        String id,
        String stageId,
        ApprovalKind kind,
        ApprovalStatus status,
        String reason,
        List<String> ruleIds,
        RiskLevel riskLevel,
        int planVersion,
        @Nullable Integer artifactVersion,
        @Nullable Integer previousAcceptedVersion,
        Instant requestedAt,
        @Nullable Instant decidedAt,
        @Nullable String decidedBy,
        @Nullable String comment,
        @Nullable Instant revokedAt,
        @Nullable String revokedReason) {

    /**
     * Normalizes collections.
     */
    public Approval {
        reason = Immutables.text(reason);
        ruleIds = Immutables.list(ruleIds);
    }

    /**
     * Creates a pending approval request.
     *
     * @param stageId gated stage
     * @param kind gate kind
     * @param reason why approval is required
     * @param ruleIds rule ids
     * @param riskLevel current risk
     * @param planVersion plan version
     * @param artifactVersion version under review, or null
     * @param previousAcceptedVersion previously accepted version, or null
     * @param at request time
     * @return a PENDING approval
     */
    public static Approval request(String stageId, ApprovalKind kind, String reason, List<String> ruleIds,
                                   RiskLevel riskLevel, int planVersion, @Nullable Integer artifactVersion,
                                   @Nullable Integer previousAcceptedVersion, Instant at) {
        return new Approval(Ids.newId(), stageId, kind, ApprovalStatus.PENDING, reason, ruleIds, riskLevel,
                planVersion, artifactVersion, previousAcceptedVersion, at, null, null, null, null, null);
    }

    /**
     * Returns an approved copy.
     *
     * @param operatorId approving operator
     * @param approvalComment comment (may be null)
     * @param at decision time
     * @return the approved approval
     */
    public Approval approve(String operatorId, @Nullable String approvalComment, Instant at) {
        return new Approval(id, stageId, kind, ApprovalStatus.APPROVED, reason, ruleIds, riskLevel, planVersion,
                artifactVersion, previousAcceptedVersion, requestedAt, at, operatorId, approvalComment, null, null);
    }

    /**
     * Returns a rejected copy.
     *
     * @param operatorId rejecting operator
     * @param rejectionReason reason
     * @param at decision time
     * @return the rejected approval
     */
    public Approval reject(String operatorId, String rejectionReason, Instant at) {
        return new Approval(id, stageId, kind, ApprovalStatus.REJECTED, reason, ruleIds, riskLevel, planVersion,
                artifactVersion, previousAcceptedVersion, requestedAt, at, operatorId, rejectionReason, null, null);
    }

    /**
     * Returns a revoked copy (decision fields are kept for the audit trail).
     *
     * @param why revocation reason
     * @param at revocation time
     * @return the revoked approval
     */
    public Approval revoke(String why, Instant at) {
        return new Approval(id, stageId, kind, ApprovalStatus.REVOKED, reason, ruleIds, riskLevel, planVersion,
                artifactVersion, previousAcceptedVersion, requestedAt, decidedAt, decidedBy, comment, at, why);
    }
}
