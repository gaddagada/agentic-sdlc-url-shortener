package com.agenticsdlc.orchestrator.core.artifact;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A named pass/fail check with details, used by verification and security reviews.
 *
 * <p>A check is <em>blocking</em> (the default) or <em>advisory</em>. In a {@link VerificationReport} a failed
 * blocking check fails verification and starts a feedback loop; a failed advisory check (style, test placement,
 * optional improvements) is reported but does not fail verification. Security reviews decide by their findings, so
 * the flag is informational there.</p>
 *
 * @param name stable check name, for example {@code no-secrets}
 * @param passed whether the check passed
 * @param details explanation or evidence
 * @param blocking whether a failure of this check fails the verification; never null after construction (a boxed
 *                 type so that JSON written before the field existed reads as blocking instead of failing)
 */
public record Check(String name, boolean passed, String details, Boolean blocking) {

    /**
     * Normalizes text fields; an absent {@code blocking} (older JSON) becomes true.
     */
    @SuppressWarnings("ConstantValue")
    public Check {
        name = Immutables.text(name);
        details = Immutables.text(details);
        blocking = blocking == null || blocking;
    }

    /**
     * Creates a blocking check.
     *
     * @param name check name
     * @param passed whether the check passed
     * @param details explanation or evidence
     */
    public Check(String name, boolean passed, String details) {
        this(name, passed, details, true);
    }

    /**
     * Creates a passing blocking check.
     *
     * @param name check name
     * @param details evidence
     * @return the check
     */
    public static Check pass(String name, String details) {
        return new Check(name, true, details);
    }

    /**
     * Creates a failing blocking check.
     *
     * @param name check name
     * @param details explanation
     * @return the check
     */
    public static Check fail(String name, String details) {
        return new Check(name, false, details);
    }

    /**
     * Creates an advisory check: its failure is reported but does not fail a verification.
     *
     * @param name check name
     * @param passed whether the check passed
     * @param details explanation or evidence
     * @return the check
     */
    public static Check advisory(String name, boolean passed, String details) {
        return new Check(name, passed, details, false);
    }

    /**
     * Returns whether this check failed and blocks a verification.
     *
     * @return true for a failed blocking check
     */
    public boolean failsBlocking() {
        return !passed && blocking;
    }
}
