package com.agenticsdlc.orchestrator.app.scenario;

/**
 * What a catalog scenario demonstrates.
 */
public enum ScenarioKind {
    /** New system from scratch. */
    GREENFIELD,
    /** Change to the existing url-shortener codebase. */
    BROWNFIELD,
    /** Under-specified requirement that needs clarification. */
    AMBIGUOUS,
    /** Requirement rejected by policy at run start. */
    POLICY_DENIAL,
    /** Transient failures recovered by retries. */
    FAULT_RETRY,
    /** Exhausted retries recovered by the deterministic fallback. */
    FAULT_FALLBACK,
    /** Requirement changed mid-run. */
    REPLAN
}
