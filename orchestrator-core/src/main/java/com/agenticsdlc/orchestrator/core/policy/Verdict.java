package com.agenticsdlc.orchestrator.core.policy;

/**
 * Outcome of a policy rule or gate, ordered by precedence: DENY > REQUIRE_APPROVAL > ALLOW.
 */
public enum Verdict {
    /** Proceed. */
    ALLOW,
    /** Proceed only after a human approval. */
    REQUIRE_APPROVAL,
    /** Do not proceed; the run safe-stops. */
    DENY;

    /**
     * Returns the verdict with the higher precedence.
     *
     * @param a first verdict
     * @param b second verdict
     * @return DENY over REQUIRE_APPROVAL over ALLOW
     */
    public static Verdict max(Verdict a, Verdict b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
