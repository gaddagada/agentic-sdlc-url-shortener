package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code CHG-002 ReleaseApproval} (EXIT of the release stage): a release always requires human approval.
 */
public final class ReleaseApprovalRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "CHG-002";

    /**
     * Creates the rule.
     */
    public ReleaseApprovalRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "ReleaseApproval";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        ReleaseReadiness release = ctx.candidate(ReleaseReadiness.class);
        if (release == null) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.LOW, Verdict.REQUIRE_APPROVAL,
                "every release requires human approval (decision " + release.decision() + ")"));
    }
}
