package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code CMP-001 PiiHandling} (EXIT of the design stage): requires approval (HIGH finding) when the design
 * processes personal data but does not define its protection or retention.
 */
public final class PiiHandlingRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "CMP-001";

    /**
     * Creates the rule.
     */
    public PiiHandlingRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "PiiHandling";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        DesignSpec design = ctx.candidate(DesignSpec.class);
        if (design == null || design.dataHandling() == null || design.dataHandling().piiFields().isEmpty()) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        if (design.dataHandling().protection().isBlank()) {
            missing.add("protection");
        }
        if (design.dataHandling().retention().isBlank()) {
            missing.add("retention");
        }
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.HIGH, Verdict.REQUIRE_APPROVAL,
                "design processes personal data (" + String.join(", ", design.dataHandling().piiFields())
                        + ") without defined " + String.join(" and ", missing)));
    }
}
