package com.agenticsdlc.orchestrator.core.artifact;

/**
 * Risk level of a change, ordered from lowest to highest.
 */
public enum RiskLevel {
    /** Low risk. */
    LOW,
    /** Medium risk (for example a public API change). */
    MEDIUM,
    /** High risk (for example schema, security or compliance relevant changes). */
    HIGH,
    /** Critical risk. */
    CRITICAL;

    /**
     * Returns whether this level is at least {@code other}.
     *
     * @param other the threshold
     * @return true when {@code this >= other}
     */
    public boolean atLeast(RiskLevel other) {
        return compareTo(other) >= 0;
    }

    /**
     * Returns the higher of two levels.
     *
     * @param a first level
     * @param b second level
     * @return the maximum
     */
    public static RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
