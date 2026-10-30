package com.agenticsdlc.orchestrator.app.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.model.RejectDisposition;

/**
 * Rejection of a pending gate.
 *
 * @param reason why (fed back to the agent on REWORK, kept in the audit trail)
 * @param disposition REWORK (the stage re-runs) or ABORT (the run is aborted)
 * @param approvalId id of the approval the operator reviewed; when given, the command is refused with 409 unless
 *                   it is still the pending approval of the stage
 */
public record RejectRequest(
        @NotBlank @Size(max = 2000) String reason,
        @NotNull RejectDisposition disposition,
        @Nullable @Pattern(regexp = ApproveRequest.APPROVAL_ID_PATTERN) String approvalId) {
}
