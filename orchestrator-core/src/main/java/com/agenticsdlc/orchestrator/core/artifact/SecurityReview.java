package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code security-scan} stage: STRIDE threat model, security checks and findings.
 * A failed review ({@code passed=false}) is still a successful stage; the release stage turns it into NO_GO.
 *
 * @param threats STRIDE threats
 * @param checks security checks
 * @param findings findings with severity
 * @param complianceNotes compliance notes
 * @param passed true when there is no HIGH or CRITICAL finding
 */
public record SecurityReview(List<Threat> threats, List<Check> checks, List<Finding> findings,
                             List<String> complianceNotes, boolean passed) implements ArtifactPayload {

    /** STRIDE categories. */
    public static final Set<String> STRIDE = Set.of("SPOOFING", "TAMPERING", "REPUDIATION", "INFORMATION_DISCLOSURE",
            "DENIAL_OF_SERVICE", "ELEVATION_OF_PRIVILEGE");

    /** Threat statuses. */
    public static final Set<String> THREAT_STATUSES = Set.of("MITIGATED", "ACCEPTED", "OPEN");

    /**
     * Normalizes collections.
     */
    public SecurityReview {
        threats = Immutables.list(threats);
        checks = Immutables.list(checks);
        findings = Immutables.list(findings);
        complianceNotes = Immutables.list(complianceNotes);
    }

    /**
     * A STRIDE threat.
     *
     * @param id threat id
     * @param category STRIDE category
     * @param description description
     * @param mitigation mitigation
     * @param status MITIGATED, ACCEPTED or OPEN
     */
    public record Threat(String id, String category, String description, String mitigation, String status) {
        /**
         * Normalizes text.
         */
        public Threat {
            id = Immutables.text(id);
            category = Immutables.text(category);
            description = Immutables.text(description);
            mitigation = Immutables.text(mitigation);
            status = Immutables.text(status);
        }
    }

    /**
     * A security finding.
     *
     * @param severity severity
     * @param title title
     * @param recommendation recommendation
     */
    public record Finding(Severity severity, String title, String recommendation) {
        /**
         * Normalizes text.
         */
        public Finding {
            title = Immutables.text(title);
            recommendation = Immutables.text(recommendation);
        }
    }

    /**
     * Computes the {@code passed} flag: no HIGH or CRITICAL finding.
     *
     * @param findings findings
     * @return whether the review passed
     */
    public static boolean computePassed(List<Finding> findings) {
        return findings.stream().noneMatch(f -> f.severity() != null && f.severity().atLeast(Severity.HIGH));
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.SECURITY_REVIEW;
    }

    @Override
    public void validate() {
        Problems p = new Problems("SecurityReview");
        p.requireNonEmpty(checks, "checks");
        for (Threat t : threats) {
            p.require(STRIDE.contains(t.category()), "threat " + t.id() + " category '" + t.category()
                    + "' must be one of " + STRIDE);
            p.require(THREAT_STATUSES.contains(t.status()), "threat " + t.id() + " status '" + t.status()
                    + "' must be one of " + THREAT_STATUSES);
        }
        for (Finding f : findings) {
            p.requireNonNull(f.severity(), "finding severity");
            p.requireText(f.title(), "finding title");
        }
        p.require(passed == computePassed(findings), "passed flag must be true exactly when there is no HIGH or "
                + "CRITICAL finding");
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Security review").field("Result", passed ? "PASSED" : "FAILED");
        md.heading(3, "Threat model (STRIDE)").table(List.of("ID", "Category", "Threat", "Mitigation", "Status"),
                threats.stream().map(t -> List.of(t.id(), t.category(), t.description(), t.mitigation(), t.status()))
                        .toList());
        md.heading(3, "Checks").table(List.of("Check", "Result", "Details"),
                checks.stream().map(c -> List.of(c.name(), c.passed() ? "pass" : "FAIL", c.details())).toList());
        md.heading(3, "Findings").table(List.of("Severity", "Finding", "Recommendation"),
                findings.stream().map(f -> List.of(String.valueOf(f.severity()), f.title(), f.recommendation()))
                        .toList());
        md.heading(3, "Compliance notes").bullets(complianceNotes);
        return md.render();
    }
}
