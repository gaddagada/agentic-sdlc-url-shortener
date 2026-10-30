package com.agenticsdlc.orchestrator.core.model;

/**
 * How much the run may proceed without humans. The release gate can never be removed.
 */
public enum AutonomyLevel {
    /** Only policy-required approvals and the release approval. */
    FULL_AUTO,
    /** Additionally, risk-based approvals (risk at least MEDIUM). */
    GUIDED,
    /** Approval at every stage entry (except intake) and at every risk-based exit. */
    STRICT;
}
