package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

import com.agenticsdlc.orchestrator.app.scenario.ScenarioDefinition;

/**
 * A catalog scenario.
 *
 * @param key scenario key
 * @param title display title
 * @param kind what it demonstrates
 * @param description description
 * @param requirement requirement text
 * @param autonomy autonomy level
 * @param workspace workspace mode
 * @param faults injected faults
 * @param walkthrough operator steps
 */
public record ScenarioView(String key, String title, String kind, String description, String requirement,
                           String autonomy, String workspace, List<FaultView> faults, List<String> walkthrough) {

    /**
     * Maps a scenario definition.
     *
     * @param definition the definition
     * @return the view
     */
    public static ScenarioView from(ScenarioDefinition definition) {
        return new ScenarioView(definition.key(), definition.title(), definition.kind().name(),
                definition.description(), definition.requirement(), definition.autonomy().name(),
                definition.workspace().name(),
                definition.faults().stream().map(f -> new FaultView(f.stageId(), f.failTimes(), f.kind().name()))
                        .toList(),
                definition.walkthrough());
    }

    /**
     * An injected fault.
     *
     * @param stageId target stage
     * @param failTimes failing attempts
     * @param kind failure kind
     */
    public record FaultView(String stageId, int failTimes, String kind) {
    }
}
