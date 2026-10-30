package com.agenticsdlc.orchestrator.app.scenario;

import java.util.List;
import java.util.Objects;

import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * An executable demo scenario: a requirement plus the run options and the steps an operator follows.
 *
 * @param key stable key used in URLs ({@code greenfield}, ...)
 * @param title display title
 * @param kind what the scenario demonstrates
 * @param description one-paragraph description
 * @param requirement the requirement text (verbatim from the engineering specification)
 * @param autonomy autonomy level
 * @param workspace workspace mode
 * @param faults faults injected into the run
 * @param walkthrough human-readable steps shown by the console
 */
public record ScenarioDefinition(String key, String title, ScenarioKind kind, String description, String requirement,
                                 AutonomyLevel autonomy, WorkspaceMode workspace, List<FaultSpec> faults,
                                 List<String> walkthrough) {

    /**
     * Validates the definition and copies the lists.
     */
    public ScenarioDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(autonomy, "autonomy");
        Objects.requireNonNull(workspace, "workspace");
        faults = List.copyOf(faults);
        walkthrough = List.copyOf(walkthrough);
    }
}
