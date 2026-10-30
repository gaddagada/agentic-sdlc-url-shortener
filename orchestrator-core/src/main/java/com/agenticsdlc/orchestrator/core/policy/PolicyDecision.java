package com.agenticsdlc.orchestrator.core.policy;

import java.util.List;
import java.util.Objects;

/**
 * Combined result of all policy rules: the verdict with the highest precedence (DENY > REQUIRE_APPROVAL >
 * ALLOW) and every finding.
 *
 * @param verdict combined verdict
 * @param findings all findings
 */
public record PolicyDecision(Verdict verdict, List<PolicyFinding> findings) {

    /**
     * Validates and copies.
     */
    public PolicyDecision {
        Objects.requireNonNull(verdict, "verdict");
        findings = List.copyOf(findings);
    }

    /**
     * Returns an ALLOW decision without findings.
     *
     * @return allow
     */
    public static PolicyDecision allow() {
        return new PolicyDecision(Verdict.ALLOW, List.of());
    }

    /**
     * Combines findings: the verdict is the maximum of the findings' verdicts (ALLOW when none).
     *
     * @param findings findings
     * @return the decision
     */
    public static PolicyDecision of(List<PolicyFinding> findings) {
        Verdict verdict = Verdict.ALLOW;
        for (PolicyFinding finding : findings) {
            verdict = Verdict.max(verdict, finding.verdict());
        }
        return new PolicyDecision(verdict, findings);
    }

    /**
     * Returns the findings with the given verdict.
     *
     * @param v verdict
     * @return findings
     */
    public List<PolicyFinding> findingsWith(Verdict v) {
        return findings.stream().filter(f -> f.verdict() == v).toList();
    }
}
