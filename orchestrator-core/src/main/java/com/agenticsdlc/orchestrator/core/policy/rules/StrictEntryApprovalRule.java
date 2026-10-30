package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.Optional;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code GOV-002 StrictEntryApproval} (ENTRY of every stage except the requirements stage): STRICT autonomy
 * requires approval before each stage starts. Starting the run is the human go-ahead for intake.
 */
public final class StrictEntryApprovalRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "GOV-002";

    /**
     * Creates the rule.
     */
    public StrictEntryApprovalRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "StrictEntryApproval";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        if (ctx.phase() != GatePhase.ENTRY || ctx.autonomy() != AutonomyLevel.STRICT
                || ctx.role() == AgentRole.REQUIREMENTS) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.LOW, Verdict.REQUIRE_APPROVAL,
                "STRICT autonomy requires approval before stage " + ctx.stageId() + " starts"));
    }
}
