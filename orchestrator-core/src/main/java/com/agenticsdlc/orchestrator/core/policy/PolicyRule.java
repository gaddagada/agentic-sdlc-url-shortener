package com.agenticsdlc.orchestrator.core.policy;

import java.util.Optional;

/**
 * A deterministic guardrail rule. Rules decide for themselves which phases and stages they apply to and
 * return an empty optional when they do not apply or find nothing.
 */
public interface PolicyRule {

    /**
     * Returns the rule id, for example {@code SEC-001}.
     *
     * @return the id
     */
    String id();

    /**
     * Returns the short rule name, for example {@code ProhibitedIntent}.
     *
     * @return the name
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Evaluates the rule.
     *
     * @param ctx context
     * @return a finding, or empty
     */
    Optional<PolicyFinding> evaluate(PolicyContext ctx);
}
