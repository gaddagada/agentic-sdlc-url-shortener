package com.agenticsdlc.orchestrator.app.scenario;

/**
 * Thrown when a scenario key is not in the catalog.
 */
public final class ScenarioNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param key the unknown key
     */
    public ScenarioNotFoundException(String key) {
        super("Scenario not found: " + key);
    }
}
