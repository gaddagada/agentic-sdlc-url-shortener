package com.agenticsdlc.orchestrator.core.workspace;

import java.io.Serial;
import java.util.List;

/**
 * Thrown when a change set cannot be applied to a sandbox because it violates the workspace rules (a CREATE of a
 * file that already exists, a path outside the allowed roots or outside the sandbox) or because a DIFF does not apply
 * to the current file (the problem names the hunk, the expected line and the nearest mismatch). Nothing is written
 * when it is thrown. The message lists every problem, so it can be fed back to the implementation agent.
 */
public final class ChangeSetRejectedException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    /**
     * Creates the exception.
     *
     * @param problems every problem found (not empty)
     */
    public ChangeSetRejectedException(List<String> problems) {
        super("change set rejected: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the problems.
     *
     * @return the problems, in change set order
     */
    public List<String> problems() {
        return problems;
    }
}
