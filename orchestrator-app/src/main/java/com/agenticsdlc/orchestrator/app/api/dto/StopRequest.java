package com.agenticsdlc.orchestrator.app.api.dto;

import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Operator stop of a run.
 *
 * @param reason optional reason
 */
public record StopRequest(@Nullable @Size(max = 2000) String reason) {
}
