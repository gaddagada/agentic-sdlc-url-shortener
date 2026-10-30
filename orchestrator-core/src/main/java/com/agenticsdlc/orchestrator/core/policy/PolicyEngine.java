package com.agenticsdlc.orchestrator.core.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.pack.DeclarativePolicyRule;
import com.agenticsdlc.orchestrator.core.policy.pack.DeclarativeRule;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackLoader;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;

/**
 * Evaluates every rule and combines the findings (DENY > REQUIRE_APPROVAL > ALLOW). The engine audits each
 * finding as {@code POLICY_FINDING}. A rule that throws fails closed: it yields a DENY finding.
 *
 * <p>An engine may carry an active {@link PolicyPack} ({@link #withDefaultRulesAnd(PolicyPack)}): its declarative
 * rules run after the built-in rules, and the orchestration engine records the pack's name, version and
 * {@link #packHash() hash} in the audit trail (data {@code policyPack} of RUN_CREATED and POLICY_DENIED).</p>
 */
public final class PolicyEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);

    private final List<PolicyRule> rules;
    private final @Nullable PolicyPack pack;
    private final @Nullable String packHash;

    /**
     * Creates an engine with the given rules and no policy pack.
     *
     * @param rules rules (ids must be unique)
     */
    public PolicyEngine(List<? extends PolicyRule> rules) {
        this(rules, null);
    }

    /**
     * Creates an engine with the given rules, recording the policy pack they came from.
     *
     * @param rules rules (ids must be unique)
     * @param pack the active policy pack (recorded for audit; its rules must already be part of {@code rules}), or
     *             null
     */
    public PolicyEngine(List<? extends PolicyRule> rules, @Nullable PolicyPack pack) {
        Set<String> ids = new HashSet<>();
        for (PolicyRule rule : rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate policy rule id " + rule.id());
            }
        }
        this.rules = List.copyOf(rules);
        this.pack = pack;
        this.packHash = pack == null ? null : pack.hash();
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
     * Creates an engine with the built-in rules minus the ones the pack disables, followed by the pack's declarative
     * rules ({@link DeclarativePolicyRule} adapters, in pack order).
     *
     * @param pack the policy pack
     * @return the engine
     * @throws com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackException when the pack is invalid (see
     *         {@link PolicyPackLoader#validate(PolicyPack)}), for example when it disables a rule of
     *         {@link PolicyPack#NON_DISABLEABLE_RULE_IDS}
     */
    public static PolicyEngine withDefaultRulesAnd(PolicyPack pack) {
        PolicyPackLoader.validate(pack);
        Set<String> disabled = Set.copyOf(pack.disabledRules());
        List<PolicyRule> rules = new ArrayList<>();
        for (PolicyRule builtIn : DefaultPolicyRules.all()) {
            if (!disabled.contains(builtIn.id())) {
                rules.add(builtIn);
            }
        }
        for (DeclarativeRule rule : pack.rules()) {
            rules.add(new DeclarativePolicyRule(pack, rule));
        }
        LOG.info("policy pack active name={} version={} hash={} rules={} disabledBuiltIns={}", pack.name(),
                pack.version(), pack.hash(), pack.rules().size(), pack.disabledRules());
        return new PolicyEngine(rules, pack);
    }

    /**
     * Returns the active policy pack.
     *
     * @return the pack, or null when only code rules are active
     */
    public @Nullable PolicyPack pack() {
        return pack;
    }

    /**
     * Returns the hash of the active policy pack ({@link PolicyPack#hash()}), computed once.
     *
     * @return the hash, or null without a pack
     */
    public @Nullable String packHash() {
        return packHash;
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
