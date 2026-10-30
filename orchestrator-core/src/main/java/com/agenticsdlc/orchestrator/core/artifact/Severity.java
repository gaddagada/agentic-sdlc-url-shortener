package com.agenticsdlc.orchestrator.core.artifact;

/**
 * Severity of a policy or security finding, ordered from least to most severe.
 */
public enum Severity {
    /** Informational or minor. */
    LOW,
    /** Should be addressed; may add release conditions. */
    MEDIUM,
    /** Must be addressed or explicitly waived by a human. */
    HIGH,
    /** Blocking. */
    CRITICAL;

    /**
     * Returns whether this severity is at least {@code other}.
     *
     * @param other the threshold
     * @return true when {@code this >= other}
     */
    public boolean atLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
