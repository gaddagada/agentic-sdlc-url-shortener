package com.agenticsdlc.orchestrator.core.graph;

/**
 * Thrown when a workflow definition is structurally invalid (duplicate ids, unknown dependencies, cycles).
 */
public final class InvalidWorkflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong
     */
    public InvalidWorkflowException(String message) {
        super(message);
    }
}
