package com.agenticsdlc.orchestrator.core.artifact;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A named pass/fail check with details, used by verification and security reviews.
 *
 * @param name stable check name, for example {@code no-secrets}
 * @param passed whether the check passed
 * @param details explanation or evidence
 */
public record Check(String name, boolean passed, String details) {

    /**
     * Normalizes text fields.
     */
    public Check {
        name = Immutables.text(name);
        details = Immutables.text(details);
    }

    /**
     * Creates a passing check.
     *
     * @param name check name
     * @param details evidence
     * @return the check
     */
    public static Check pass(String name, String details) {
        return new Check(name, true, details);
    }

    /**
     * Creates a failing check.
     *
     * @param name check name
     * @param details explanation
     * @return the check
     */
    public static Check fail(String name, String details) {
        return new Check(name, false, details);
    }
}
