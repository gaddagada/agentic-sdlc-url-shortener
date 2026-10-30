package com.agenticsdlc.orchestrator.app.api;

/**
 * The requested operation is switched off by configuration in this deployment (for example the evaluation suite with
 * {@code sdlc.evaluation.enabled=false}); mapped to 404 with the property to change in the detail.
 */
public final class FeatureDisabledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is disabled and which property enables it (returned to the client)
     */
    public FeatureDisabledException(String message) {
        super(message);
    }
}
