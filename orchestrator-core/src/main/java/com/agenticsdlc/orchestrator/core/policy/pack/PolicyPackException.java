package com.agenticsdlc.orchestrator.core.policy.pack;

import java.util.List;

/**
 * A policy pack is malformed or violates a governance constraint. The message lists every problem, each prefixed
 * with the JSON path it refers to (for example {@code $.rules[1].when.regexes[0]: invalid regex ...}).
 */
public final class PolicyPackException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The problems (never empty). */
    private final List<String> problems;

    /**
     * Creates the exception.
     *
     * @param problems the problems (not empty)
     */
    public PolicyPackException(List<String> problems) {
        super("invalid policy pack: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the problems.
     *
     * @return one entry per problem, prefixed with its JSON path
     */
    public List<String> problems() {
        return problems;
    }
}
