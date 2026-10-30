package com.agenticsdlc.orchestrator.core.graph;

/**
 * Whether a gate requires human approval.
 */
public enum ApprovalRequirement {
    /** No approval from the gate itself (policy rules may still require one). */
    NEVER,
    /**
     * Approval depends on risk and autonomy: never in FULL_AUTO, always in STRICT, and in GUIDED when the
     * run's current risk is at least MEDIUM and exceeds the risk already accepted by a human approval in
     * the current plan version.
     */
    RISK_BASED,
    /** Always requires approval, regardless of autonomy. */
    ALWAYS
}
