package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Something a human must do before the run can continue: approve or reject a gate ({@code APPROVAL}) or answer
 * clarification questions ({@code CLARIFICATION}). Fields that do not apply to the type are omitted.
 *
 * @param type APPROVAL or CLARIFICATION
 * @param stageId the waiting stage
 * @param gateKind ENTRY or EXIT (approvals)
 * @param approvalId approval id (approvals)
 * @param reason why approval is required (approvals)
 * @param ruleIds policy rules that required approval (approvals)
 * @param requestedAt when the action was requested
 * @param questions the open questions (clarifications)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingActionView(
        String type,
        String stageId,
        @Nullable String gateKind,
        @Nullable String approvalId,
        @Nullable String reason,
        @Nullable List<String> ruleIds,
        @Nullable Instant requestedAt,
        @Nullable List<ClarificationQuestionView> questions) {

    /** Type of an approval action. */
    public static final String APPROVAL = "APPROVAL";

    /** Type of a clarification action. */
    public static final String CLARIFICATION = "CLARIFICATION";
}
