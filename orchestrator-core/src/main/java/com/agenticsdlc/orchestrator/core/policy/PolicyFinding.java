package com.agenticsdlc.orchestrator.core.policy;

import java.util.Objects;

import com.agenticsdlc.orchestrator.core.artifact.Severity;

/**
 * A finding produced by a policy rule.
 *
 * @param ruleId rule id, for example {@code SEC-001}
 * @param severity severity
 * @param verdict verdict
 * @param message message (never contains secret values)
 */
public record PolicyFinding(String ruleId, Severity severity, Verdict verdict, String message) {

    /**
     * Validates the finding.
     */
    public PolicyFinding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(message, "message");
    }
}
