package com.agenticsdlc.orchestrator.core.agent;

import java.util.Objects;

/**
 * A compensation an agent asks the engine to register for a side effect it produced. The engine executes
 * it (through a compensation handler for {@code action}) if the run is rolled back past this stage.
 *
 * @param action action name, for example {@code DELETE_SANDBOX}
 * @param target action target, for example a directory
 * @param description human-readable description
 */
public record CompensationRequest(String action, String target, String description) {

    /**
     * Validates the request.
     */
    public CompensationRequest {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(description, "description");
    }
}
