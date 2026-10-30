package com.agenticsdlc.orchestrator.app.api;

/**
 * Thrown when a run has no artifact for the requested stage or version (mapped to 404).
 */
public final class ArtifactNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was not found
     */
    public ArtifactNotFoundException(String message) {
        super(message);
    }
}
