package com.agenticsdlc.orchestrator.app.api;

/**
 * An evaluation suite run is already in progress on this node (one at a time); mapped to 409.
 */
public final class EvaluationInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     */
    public EvaluationInProgressException() {
        super("An evaluation suite run is already in progress on this node; retry when it has finished.");
    }
}
