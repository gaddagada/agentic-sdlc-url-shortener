package com.agenticsdlc.orchestrator.app.api;

/**
 * Thrown when an approval would break the four-eyes rule ({@code sdlc.governance.four-eyes=true}): the release
 * must be approved by someone other than the operator who started the run and the operator who approved the
 * design (mapped to 409).
 */
public final class FourEyesViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the approval is refused
     */
    public FourEyesViolationException(String message) {
        super(message);
    }
}
