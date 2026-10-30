package com.agenticsdlc.orchestrator.core.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;

/**
 * Evaluates every rule and combines the findings (DENY > REQUIRE_APPROVAL > ALLOW). The engine audits each
 * finding as {@code POLICY_FINDING}. A rule that throws fails closed: it yields a DENY finding.
 */
public final class PolicyEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);

    private final List<PolicyRule> rules;

    /**
     * Creates an engine with the given rules.
     *
     * @param rules rules (ids must be unique)
     */
    public PolicyEngine(List<? extends PolicyRule> rules) {
        Set<String> ids = new HashSet<>();
        for (PolicyRule rule : rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate policy rule id " + rule.id());
            }
        }
        this.rules = List.copyOf(rules);
    }

    /**
     * Creates an engine with all built-in rules ({@link DefaultPolicyRules#all()}).
     *
     * @return the engine
     */
    public static PolicyEngine withDefaultRules() {
        return new PolicyEngine(DefaultPolicyRules.all());
    }

    /**
     * Returns the rules.
     *
     * @return rules
     */
    public List<PolicyRule> rules() {
        return rules;
    }

    /**
     * Evaluates all rules.
     *
     * @param ctx context
     * @return the combined decision
     */
    public PolicyDecision evaluate(PolicyContext ctx) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (PolicyRule rule : rules) {
            try {
                Optional<PolicyFinding> finding = rule.evaluate(ctx);
                finding.ifPresent(findings::add);
            } catch (RuntimeException e) {
                LOG.error("policy rule failed, failing closed ruleId={} phase={} stageId={}", rule.id(), ctx.phase(),
                        ctx.stageId(), e);
                findings.add(new PolicyFinding(rule.id(), Severity.CRITICAL, Verdict.DENY,
                        "rule evaluation failed (" + e.getClass().getSimpleName() + "); failing closed"));
            }
        }
        return PolicyDecision.of(findings);
    }
}
