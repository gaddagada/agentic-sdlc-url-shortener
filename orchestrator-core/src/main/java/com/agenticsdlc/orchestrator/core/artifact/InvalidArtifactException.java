package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

/**
 * Thrown by {@link ArtifactPayload#validate()} when a payload violates its invariants. The message lists
 * every problem so that an LLM agent can include it in the next attempt's prompt.
 */
public final class InvalidArtifactException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    /**
     * Creates the exception.
     *
     * @param payloadName the payload type name
     * @param problems the problems found (non-empty)
     */
    public InvalidArtifactException(String payloadName, List<String> problems) {
        super(payloadName + " is invalid: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the individual problems.
     *
     * @return problems
     */
    public List<String> problems() {
        return problems;
    }
}
