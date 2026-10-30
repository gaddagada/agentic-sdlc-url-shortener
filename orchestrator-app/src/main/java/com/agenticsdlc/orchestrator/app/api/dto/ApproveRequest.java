package com.agenticsdlc.orchestrator.app.api.dto;

import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Approval of a pending gate.
 *
 * @param comment optional comment (required as a waiver when approving a NO_GO release)
 */
public record ApproveRequest(@Nullable @Size(max = 2000) String comment) {
}
