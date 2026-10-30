package com.agenticsdlc.orchestrator.app.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.PolicyView;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.pack.DeclarativeRule;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import tools.jackson.core.type.TypeReference;

/**
 * The policy in force: built-in rules and the policy pack of {@code sdlc.policy.pack}, if any. Readable by every
 * role.
 */
@RestController
@RequestMapping("/api/v1/policy")
@Tag(name = "Policy", description = "Built-in rules and the active policy pack")
public class PolicyController {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final PolicyView view;

    /**
     * Creates the controller; the policy is fixed at startup, so the view is built once.
     *
     * @param policyEngine the engine's policy engine
     * @param codec JSON codec (renders the pack's conditions)
     */
    public PolicyController(PolicyEngine policyEngine, JsonCodec codec) {
        this.view = view(policyEngine, codec);
    }

    /**
     * Returns the policy in force.
     *
     * @return the policy
     */
    @GetMapping
    @Operation(summary = "Get the policy in force (built-in rules and the active policy pack)",
            operationId = "getPolicy")
    public PolicyView policy() {
        return view;
    }

    /**
     * Builds the policy view.
     *
     * @param policyEngine the policy engine
     * @param codec JSON codec
     * @return the view
     */
    static PolicyView view(PolicyEngine policyEngine, JsonCodec codec) {
        List<String> active = policyEngine.rules().stream().map(PolicyRule::id).toList();
        Set<String> activeIds = Set.copyOf(active);
        List<PolicyView.BuiltInRuleView> builtIns = DefaultPolicyRules.all().stream()
                .map(rule -> new PolicyView.BuiltInRuleView(rule.id(), rule.name(), activeIds.contains(rule.id()),
                        !PolicyPack.NON_DISABLEABLE_RULE_IDS.contains(rule.id())))
                .toList();
        PolicyPack pack = policyEngine.pack();
        PolicyView.PackView packView = null;
        if (pack != null) {
            String hash = policyEngine.packHash() == null ? pack.hash() : policyEngine.packHash();
            packView = new PolicyView.PackView(pack.name(), pack.version(), hash, pack.disabledRules(),
                    pack.rules().stream().map(rule -> rule(rule, codec)).toList());
        }
        return new PolicyView(builtIns, active, packView);
    }

    private static PolicyView.PackRuleView rule(DeclarativeRule rule, JsonCodec codec) {
        return new PolicyView.PackRuleView(rule.id(), rule.description(), rule.phase().name(), rule.stages(),
                rule.effect().name(), rule.severity().name(), rule.message(),
                codec.mapper().convertValue(rule.when(), OBJECT));
    }
}
