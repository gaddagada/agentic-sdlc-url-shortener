package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code GOV-001 RiskBasedDesignApproval} (EXIT of the design stage): requires design approval when the
 * autonomy is GUIDED and the risk is at least MEDIUM, or when the autonomy is STRICT.
 */
public final class RiskBasedDesignApprovalRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "GOV-001";

    /**
     * Creates the rule.
     */
    public RiskBasedDesignApprovalRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "RiskBasedDesignApproval";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        DesignSpec design = ctx.candidate(DesignSpec.class);
        if (design == null) {
            return Optional.empty();
        }
        RiskAssessment risk = ctx.runSnapshot().currentRisk();
        if (design.risk() != null) {
            risk = risk.merge(design.risk());
        }
        String factors = risk.factors().isEmpty() ? "" : " (" + String.join(", ", risk.factors()) + ")";
        if (ctx.autonomy() == AutonomyLevel.STRICT) {
            return Optional.of(new PolicyFinding(ID, Severity.MEDIUM, Verdict.REQUIRE_APPROVAL,
                    "STRICT autonomy requires design approval; risk " + risk.level() + factors));
        }
        if (ctx.autonomy() == AutonomyLevel.GUIDED && risk.level().atLeast(RiskLevel.MEDIUM)) {
            return Optional.of(new PolicyFinding(ID, Severity.MEDIUM, Verdict.REQUIRE_APPROVAL,
                    "risk " + risk.level() + factors + " requires design approval in GUIDED autonomy"));
        }
        return Optional.empty();
    }
}
