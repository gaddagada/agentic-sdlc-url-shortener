package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.model.Approval;

/**
 * A gate approval request and its decision.
 *
 * @param id approval id
 * @param kind ENTRY or EXIT
 * @param status PENDING, APPROVED, REJECTED or REVOKED
 * @param reason why approval is required
 * @param ruleIds policy rules that required it
 * @param riskLevel run risk when requested
 * @param planVersion plan version when requested
 * @param artifactVersion artifact version under review (exit gates); accepted only once approved
 * @param previousAcceptedVersion version that was accepted when approval was requested (stays accepted until the
 *                                version under review is approved)
 * @param artifactHash SHA-256 hash of the artifact version under review (exit gates)
 * @param requestedAt request time
 * @param decidedAt decision time
 * @param decidedBy deciding operator
 * @param comment approval comment or rejection reason
 * @param revokedAt revocation time
 * @param revokedReason revocation reason
 */
public record ApprovalView(
        String id,
        String kind,
        String status,
        String reason,
        List<String> ruleIds,
        String riskLevel,
        int planVersion,
        @Nullable Integer artifactVersion,
        @Nullable Integer previousAcceptedVersion,
        @Nullable String artifactHash,
        Instant requestedAt,
        @Nullable Instant decidedAt,
        @Nullable String decidedBy,
        @Nullable String comment,
        @Nullable Instant revokedAt,
        @Nullable String revokedReason) {

    /**
     * Maps a core approval.
     *
     * @param approval the approval
     * @return the view
     */
    public static ApprovalView from(Approval approval) {
        return new ApprovalView(approval.id(), approval.kind().name(), approval.status().name(), approval.reason(),
                approval.ruleIds(), approval.riskLevel().name(), approval.planVersion(), approval.artifactVersion(),
                approval.previousAcceptedVersion(), approval.artifactHash(), approval.requestedAt(), approval.decidedAt(), approval.decidedBy(), approval.comment(),
                approval.revokedAt(), approval.revokedReason());
    }
}
