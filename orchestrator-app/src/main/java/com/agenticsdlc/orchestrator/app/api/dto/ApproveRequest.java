package com.agenticsdlc.orchestrator.app.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Approval of a pending gate.
 *
 * @param comment optional comment (required as a waiver when approving a NO_GO release)
 * @param approvalId id of the approval the operator reviewed ({@code pendingActions[].approvalId}); when given,
 *                   the command is refused with 409 unless it is still the pending approval of the stage, so a
 *                   decision can never apply to an artifact version the operator did not see
 */
public record ApproveRequest(
        @Nullable @Size(max = 2000) String comment,
        @Nullable @Pattern(regexp = ApproveRequest.APPROVAL_ID_PATTERN) String approvalId) {

    /** Allowed approval ids. */
    public static final String APPROVAL_ID_PATTERN = "[A-Za-z0-9._-]{1,64}";
}
