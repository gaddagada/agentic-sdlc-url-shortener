package com.agenticsdlc.orchestrator.app.api;

/**
 * This node already serves the maximum number of server-sent event streams ({@code sdlc.console.max-sse-connections});
 * mapped to 503 with a {@code Retry-After} header. The console falls back to polling.
 */
public final class StreamLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param max the configured maximum
     */
    public StreamLimitException(int max) {
        super("This node already serves " + max + " event streams (sdlc.console.max-sse-connections); poll the "
                + "REST endpoints or retry later.");
    }
}
