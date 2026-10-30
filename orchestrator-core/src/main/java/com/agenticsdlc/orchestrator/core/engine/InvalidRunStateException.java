package com.agenticsdlc.orchestrator.core.engine;

/**
 * Thrown when the engine refuses a command because of the current state of the run or stage: approving or
 * rejecting a stage that is not awaiting approval (or whose pending approval is not the one the operator
 * reviewed), clarifying when nothing awaits clarification, stopping a run that is not active, resuming a run
 * that is not SAFE_STOPPED or was stopped by a policy denial, re-planning a terminal run, or issuing a
 * governance command on a run that is not active.
 *
 * <p>It extends {@link IllegalStateException} so existing handlers keep working; adapters should map it to a
 * conflict (HTTP 409). {@link InvalidCommandException} remains the type for invalid arguments, and engine
 * lifecycle problems (closed or shutting-down engine) are reported as plain {@link IllegalStateException}. The
 * subclass {@link RunLeasedElsewhereException} reports a run that another engine node is executing.</p>
 */
public class InvalidRunStateException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the state
     */
    public InvalidRunStateException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause (for example the store exception of a commit that failed, which the engine
     * reports as a conflict with the stored state of the run).
     *
     * @param message what is wrong with the state
     * @param cause the cause
     */
    public InvalidRunStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
