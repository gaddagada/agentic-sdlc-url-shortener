package com.agenticsdlc.orchestrator.app.api;

/**
 * A request the API layer rejects with a message written by the control plane itself (mapped to 400 with that
 * message). Other {@link IllegalArgumentException}s are answered with a generic detail, because their messages
 * may come from libraries and expose internals.
 */
public final class InvalidRequestException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the request (returned to the client)
     */
    public InvalidRequestException(String message) {
        super(message);
    }
}
