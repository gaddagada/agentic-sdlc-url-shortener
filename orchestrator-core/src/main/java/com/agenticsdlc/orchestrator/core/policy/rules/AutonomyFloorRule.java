package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code GOV-003 AutonomyFloor} (EXIT of the release stage): even FULL_AUTO requires release approval. Same
 * effect as {@code CHG-002}; present to make the autonomy boundary explicit in the audit trail.
 */
public final class AutonomyFloorRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "GOV-003";

    /**
     * Creates the rule.
     */
    public AutonomyFloorRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "AutonomyFloor";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        if (ctx.candidate(ReleaseReadiness.class) == null || ctx.autonomy() != AutonomyLevel.FULL_AUTO) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.LOW, Verdict.REQUIRE_APPROVAL,
                "FULL_AUTO autonomy cannot remove the release approval gate"));
    }
}
