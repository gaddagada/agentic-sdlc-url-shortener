package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code SEC-003 ProtectedPath} (EXIT of the implementation stage): requires approval when a change touches
 * a protected path: {@code **}{@code /security/**}, {@code **}{@code /application-prod*.yml},
 * {@code .github/**} or {@code **}{@code /pom.xml} (a leading {@code **}{@code /} also matches the
 * repository root).
 */
public final class ProtectedPathRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "SEC-003";

    /** Protected path globs. */
    public static final List<String> PROTECTED_GLOBS = List.of(
            "**/security/**", "**/application-prod*.yml", ".github/**", "**/pom.xml");

    private static final List<Pattern> PATTERNS = PROTECTED_GLOBS.stream().map(Globs::toPattern).toList();

    /**
     * Creates the rule.
     */
    public ProtectedPathRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "ProtectedPath";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        ChangeSet changeSet = ctx.candidate(ChangeSet.class);
        if (changeSet == null) {
            return Optional.empty();
        }
        List<String> hits = new ArrayList<>();
        for (ChangeSet.FileChange change : changeSet.changes()) {
            if (PATTERNS.stream().anyMatch(p -> p.matcher(change.path()).matches())) {
                hits.add(change.path());
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.HIGH, Verdict.REQUIRE_APPROVAL,
                "change touches protected paths: " + String.join(", ", hits)));
    }
}
