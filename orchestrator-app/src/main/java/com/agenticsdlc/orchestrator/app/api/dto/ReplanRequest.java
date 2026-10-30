package com.agenticsdlc.orchestrator.app.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Re-plan with a changed requirement.
 *
 * @param requirement the changed requirement (10 to 4000 characters)
 */
public record ReplanRequest(@NotBlank @Size(min = 10, max = 4000) String requirement) {
}
