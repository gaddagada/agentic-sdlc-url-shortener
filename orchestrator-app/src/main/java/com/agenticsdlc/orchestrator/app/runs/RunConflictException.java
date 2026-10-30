package com.agenticsdlc.orchestrator.app.runs;

import java.io.Serial;

/**
 * A write of a run was refused because the stored run changed after this node read it: another engine node (or
 * another writer) wrote the run first. Thrown by the persistence layer, for example when an audit batch does not
 * continue the stored hash chain or collides with stored sequence numbers; the transaction is rolled back, so
 * nothing of the refused write is stored. The web layer answers it with 409 {@code run-conflict}.
 *
 * <p>It is an {@link IllegalStateException}, the type the engine's ports use for refused writes.</p>
 */
public class RunConflictException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was refused (names the run; no secrets)
     */
    public RunConflictException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the store error that revealed the conflict.
     *
     * @param message what was refused (names the run; no secrets)
     * @param cause the store error
     */
    public RunConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
