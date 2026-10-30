package com.agenticsdlc.orchestrator.core.engine;

/**
 * Thrown when a run id is unknown to the engine and its store.
 */
public final class RunNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String runId;

    /**
     * Creates the exception.
     *
     * @param runId the unknown run id
     */
    public RunNotFoundException(String runId) {
        super("Run not found: " + runId);
        this.runId = runId;
    }

    /**
     * Returns the unknown run id.
     *
     * @return run id
     */
    public String runId() {
        return runId;
    }
}
