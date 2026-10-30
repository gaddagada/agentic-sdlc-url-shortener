package com.agenticsdlc.orchestrator.core.engine;

/**
 * Thrown when a command is invalid regardless of the run's state (blank requirement, unknown stage or
 * provider, missing clarification answers, non-human actor for a governance action, exhausted command
 * budget, missing waiver comment). Commands issued in the wrong run or stage state throw
 * {@link IllegalStateException} instead.
 */
public final class InvalidCommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong
     */
    public InvalidCommandException(String message) {
        super(message);
    }
}
