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
 * a protected path ({@link #PROTECTED_GLOBS}): security code, production configuration, CI, build files and the
 * Maven wrapper, container definitions, and the orchestrator's own modules ({@code orchestrator-core/**},
 * {@code orchestrator-app/**}: self-modification of the control plane always needs a human). A leading
 * {@code **}{@code /} also matches the repository root. Paths are normalized before matching (backslashes,
 * {@code ./} and {@code //} segments, leading slashes) and globs match case-insensitively, so spellings such as
 * {@code ./POM.xml} cannot bypass the rule.
 */
public final class ProtectedPathRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "SEC-003";

    /** Protected path globs. */
    public static final List<String> PROTECTED_GLOBS = List.of(
            "**/security/**", "**/application-prod*.yml", ".github/**", "**/pom.xml",
            "**/mvnw", "**/mvnw.cmd", "**/.mvn/**",
            "orchestrator-core/**", "orchestrator-app/**",
            "**/Dockerfile*", "**/docker-compose*.yml", "**/docker-compose*.yaml");

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
            if (isProtected(change.path())) {
                hits.add(change.path());
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.HIGH, Verdict.REQUIRE_APPROVAL,
                "change touches protected paths: " + String.join(", ", hits)));
    }

    /**
     * Returns whether a repository path is protected (normalized, case-insensitive glob match).
     *
     * @param path repository-relative path as written by an agent
     * @return true when a change of it requires approval
     */
    public static boolean isProtected(String path) {
        String normalized = Globs.normalize(path);
        return PATTERNS.stream().anyMatch(p -> p.matcher(normalized).matches());
    }
}
