package com.agenticsdlc.orchestrator.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Options of a run.
 *
 * @param autonomy autonomy level
 * @param providerId agent provider id ({@code null} or {@code deterministic} for the deterministic agents)
 * @param faults faults to inject
 * @param budgetOverride budget override, or {@code null} for {@link RunBudget#DEFAULT}
 * @param workspace whether the repository workspace is analysed
 * @param tags free-form tags
 */
public record RunOptions(AutonomyLevel autonomy, @Nullable String providerId, List<FaultSpec> faults,
                         @Nullable RunBudget budgetOverride, WorkspaceMode workspace, Map<String, String> tags) {

    /**
     * Normalizes fields: null autonomy becomes GUIDED, null workspace becomes NONE, null collections empty.
     */
    public RunOptions {
        autonomy = Objects.requireNonNullElse(autonomy, AutonomyLevel.GUIDED);
        workspace = Objects.requireNonNullElse(workspace, WorkspaceMode.NONE);
        faults = Immutables.list(faults);
        tags = Immutables.map(tags);
    }

    /**
     * Returns default options: GUIDED, deterministic provider, no faults, default budget, no workspace.
     *
     * @return defaults
     */
    public static RunOptions defaults() {
        return new RunOptions(AutonomyLevel.GUIDED, null, List.of(), null, WorkspaceMode.NONE, Map.of());
    }

    /**
     * Returns a copy with a different autonomy level.
     *
     * @param value autonomy
     * @return the copy
     */
    public RunOptions withAutonomy(AutonomyLevel value) {
        return new RunOptions(value, providerId, faults, budgetOverride, workspace, tags);
    }

    /**
     * Returns a copy with a different provider.
     *
     * @param value provider id or null
     * @return the copy
     */
    public RunOptions withProvider(@Nullable String value) {
        return new RunOptions(autonomy, value, faults, budgetOverride, workspace, tags);
    }

    /**
     * Returns a copy with different faults.
     *
     * @param value faults
     * @return the copy
     */
    public RunOptions withFaults(List<FaultSpec> value) {
        return new RunOptions(autonomy, providerId, value, budgetOverride, workspace, tags);
    }

    /**
     * Returns a copy with a budget override.
     *
     * @param value budget or null
     * @return the copy
     */
    public RunOptions withBudget(@Nullable RunBudget value) {
        return new RunOptions(autonomy, providerId, faults, value, workspace, tags);
    }

    /**
     * Returns a copy with a different workspace mode.
     *
     * @param value workspace mode
     * @return the copy
     */
    public RunOptions withWorkspace(WorkspaceMode value) {
        return new RunOptions(autonomy, providerId, faults, budgetOverride, value, tags);
    }

    /**
     * Returns the fault specification for a stage, if any.
     *
     * @param stageId stage id
     * @return the fault
     */
    public Optional<FaultSpec> faultFor(String stageId) {
        return faults.stream().filter(f -> f.stageId().equals(stageId)).findFirst();
    }
}
