package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.List;

import com.agenticsdlc.orchestrator.core.policy.PolicyRule;

/**
 * The built-in rule set.
 */
public final class DefaultPolicyRules {

    private DefaultPolicyRules() {
    }

    /**
     * Returns all built-in rules: SEC-001, SEC-002, SEC-003, CMP-001, CMP-002, CHG-001, CHG-002, CHG-003,
     * GOV-001, GOV-002, GOV-003.
     *
     * @return the rules
     */
    public static List<PolicyRule> all() {
        return List.of(
                new ProhibitedIntentRule(),
                new SecretLeakRule(),
                new ProtectedPathRule(),
                new PiiHandlingRule(),
                new PiiProhibitedRule(),
                new ChangeSizeRule(),
                new ReleaseApprovalRule(),
                new NoGoReleaseRule(),
                new RiskBasedDesignApprovalRule(),
                new StrictEntryApprovalRule(),
                new AutonomyFloorRule());
    }

    /**
     * Returns the ids of all built-in rules, in the order of {@link #all()}.
     *
     * @return the ids
     */
    public static List<String> ids() {
        return all().stream().map(PolicyRule::id).toList();
    }
}
