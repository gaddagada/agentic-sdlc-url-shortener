package com.agenticsdlc.orchestrator.core.policy.pack;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.policy.rules.AutonomyFloorRule;
import com.agenticsdlc.orchestrator.core.policy.rules.ReleaseApprovalRule;
import com.agenticsdlc.orchestrator.core.policy.rules.SecretLeakRule;
import com.agenticsdlc.orchestrator.core.util.Hashes;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Policy as code: a named, versioned set of declarative rules that the engine evaluates next to the built-in rules,
 * plus the ids of built-in rules the pack disables. Load packs with {@link PolicyPackLoader#fromJson(String)} (strict:
 * unknown fields, bad regexes or globs, duplicate ids, collisions with built-in ids and attempts to disable a
 * protected built-in rule are rejected) and activate one with
 * {@link com.agenticsdlc.orchestrator.core.policy.PolicyEngine#withDefaultRulesAnd(PolicyPack)}.
 *
 * <p>JSON form (YAML is a superset; the application converts YAML to JSON before loading):</p>
 * <pre>{@code
 * {
 *   "name": "acme-baseline",
 *   "version": "1.2.0",
 *   "disabledRules": ["CHG-001"],
 *   "rules": [{
 *     "id": "ORG-001",
 *     "description": "Security code needs a human",
 *     "phase": "EXIT",
 *     "stages": ["implement"],
 *     "when": {"type": "pathMatches", "globs": ["src/security/**"]},
 *     "effect": "REQUIRE_APPROVAL",
 *     "severity": "HIGH",
 *     "message": "change touches security code"
 *   }]
 * }
 * }</pre>
 *
 * @param name pack name (not blank)
 * @param version pack version (not blank)
 * @param rules declarative rules
 * @param disabledRules ids of built-in rules to disable (never one of {@link #NON_DISABLEABLE_RULE_IDS})
 */
public record PolicyPack(String name, String version, List<DeclarativeRule> rules, List<String> disabledRules) {

    /**
     * Built-in rules a pack can never disable: the release gate ({@code CHG-002 ReleaseApproval} and
     * {@code GOV-003 AutonomyFloor}, so every release keeps its human approval) and the secret-leak guard
     * ({@code SEC-002 SecretLeak}, so no artifact that embeds a credential is ever accepted).
     */
    public static final Set<String> NON_DISABLEABLE_RULE_IDS = Set.of(ReleaseApprovalRule.ID, AutonomyFloorRule.ID,
            SecretLeakRule.ID);

    /**
     * Validates the required fields and copies the lists.
     */
    public PolicyPack {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        rules = Immutables.list(rules);
        disabledRules = Immutables.list(disabledRules);
    }

    /**
     * Returns the SHA-256 (lowercase hex) of the pack's strict canonical JSON
     * ({@link JsonCodec#strictCanonicalJson(Object)}): the same pack always has the same hash, whatever the key order
     * or whitespace of its source, and any change of a rule changes it. The engine records it in the audit trail.
     *
     * @return the hash
     */
    public String hash() {
        return Hashes.sha256Hex(JsonCodec.standard().strictCanonicalJson(this));
    }
}
