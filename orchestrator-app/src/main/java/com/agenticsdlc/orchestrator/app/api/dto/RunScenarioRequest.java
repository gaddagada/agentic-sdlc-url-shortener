package com.agenticsdlc.orchestrator.app.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Optional overrides for a scenario run; the scenario itself (requirement, autonomy, workspace, faults) is fixed.
 *
 * @param provider agent provider (default: the {@code provider} query parameter, else
 *                 {@code sdlc.agents.default-provider})
 * @param budget budget overrides (unset values keep the defaults)
 */
public record RunScenarioRequest(
        @Nullable @Size(max = 32) String provider,
        @Valid CreateRunRequest.@Nullable BudgetRequest budget) {
}
