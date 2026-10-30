package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code CHG-003 NoGoRelease} (EXIT of the release stage): a NO_GO decision requires approval with a HIGH
 * finding; humans may still approve, but only with a waiver comment (enforced by the engine), which is
 * recorded in the change record.
 */
public final class NoGoReleaseRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "CHG-003";

    /**
     * Creates the rule.
     */
    public NoGoReleaseRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "NoGoRelease";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        ReleaseReadiness release = ctx.candidate(ReleaseReadiness.class);
        if (release == null || release.decision() != ReleaseReadiness.GoNoGo.NO_GO) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.HIGH, Verdict.REQUIRE_APPROVAL,
                "release decision is NO_GO; approval requires a waiver comment"));
    }
}
