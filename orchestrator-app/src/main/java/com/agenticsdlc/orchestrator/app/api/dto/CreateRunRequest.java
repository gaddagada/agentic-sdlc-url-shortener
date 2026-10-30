package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * Request to start a run.
 *
 * @param requirement the requirement (10 to 4000 characters)
 * @param scenario scenario key recorded on the run (default {@code custom})
 * @param autonomy autonomy level (default GUIDED)
 * @param provider agent provider (default: {@code sdlc.agents.default-provider})
 * @param faults faults to inject (fault-injection demos)
 * @param workspace workspace mode (default REPO)
 * @param budget budget overrides
 */
public record CreateRunRequest(
        @NotBlank @Size(min = 10, max = 4000) String requirement,
        @Nullable @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]*") String scenario,
        @Nullable AutonomyLevel autonomy,
        @Nullable @Size(max = 32) String provider,
        @Nullable @Size(max = 10) List<@Valid @NotNull FaultRequest> faults,
        @Nullable WorkspaceMode workspace,
        @Nullable @Valid BudgetRequest budget) {

    /**
     * A fault to inject into a stage.
     *
     * @param stageId stage id
     * @param failTimes how many attempts fail (1..99)
     * @param kind failure kind
     */
    public record FaultRequest(
            @NotBlank @Size(max = 64) String stageId,
            @Min(1) @Max(99) int failTimes,
            @NotNull FaultKind kind) {
    }

    /**
     * Budget overrides; unset values keep the defaults.
     *
     * @param maxFeedbackLoops maximum feedback loops (0..10)
     * @param maxReplans maximum re-plans (0..10)
     * @param maxAgentCalls maximum agent calls (1..500)
     * @param maxLlmTokens maximum LLM tokens (0..10 000 000)
     */
    public record BudgetRequest(
            @Nullable @Min(0) @Max(10) Integer maxFeedbackLoops,
            @Nullable @Min(0) @Max(10) Integer maxReplans,
            @Nullable @Min(1) @Max(500) Integer maxAgentCalls,
            @Nullable @Min(0) @Max(10_000_000) Long maxLlmTokens) {
    }
}
