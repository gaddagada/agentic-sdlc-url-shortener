package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The policy the control plane enforces: the built-in rules (and whether each is active) and, when one is configured
 * ({@code sdlc.policy.pack}), the policy pack with its declarative rules and the built-in rules it disables.
 *
 * @param builtInRules every built-in rule, in evaluation order
 * @param activeRuleIds ids of the rules the engine evaluates, in evaluation order (built-in rules first)
 * @param pack the active policy pack, or null when only the built-in rules apply
 */
public record PolicyView(List<BuiltInRuleView> builtInRules, List<String> activeRuleIds, @Nullable PackView pack) {

    /**
     * A built-in rule.
     *
     * @param id rule id, for example {@code SEC-001}
     * @param name rule name, for example {@code ProhibitedIntent}
     * @param active false when the policy pack disables it
     * @param disableable false for the rules no pack may disable (release approval, autonomy floor, secret leak)
     */
    public record BuiltInRuleView(String id, String name, boolean active, boolean disableable) {
    }

    /**
     * The active policy pack. Its name, version and hash are also recorded in the audit trail (data
     * {@code policyPack} of RUN_CREATED and POLICY_DENIED events).
     *
     * @param name pack name
     * @param version pack version
     * @param hash SHA-256 of the pack's canonical JSON
     * @param disabledRules ids of the built-in rules the pack disables
     * @param rules the pack's declarative rules, in evaluation order
     */
    public record PackView(String name, String version, String hash, List<String> disabledRules,
                           List<PackRuleView> rules) {
    }

    /**
     * A declarative rule of the pack.
     *
     * @param id rule id
     * @param description what the rule guards
     * @param phase gate phase ({@code RUN_START}, {@code ENTRY}, {@code EXIT})
     * @param stages stage ids the rule applies to (empty for every stage)
     * @param effect {@code DENY}, {@code REQUIRE_APPROVAL} or {@code WARN}
     * @param severity severity of its findings
     * @param message finding message
     * @param when the condition, as written in the pack (an object whose {@code type} names the condition)
     */
    public record PackRuleView(String id, String description, String phase, List<String> stages, String effect,
                               String severity, String message, Map<String, Object> when) {
    }
}
