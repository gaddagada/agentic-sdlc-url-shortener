package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code security-scan} stage: STRIDE threat model, security checks and findings.
 * A failed review ({@code passed=false}) is still a successful stage; the release stage turns it into NO_GO.
 *
 * <p>{@code dependencies} is the dependency inventory of the workspace's Maven build files (REPO runs). It is always
 * filled by the engine's agents from the build files themselves (an LLM review may leave it empty; whatever a model
 * writes there is replaced), together with a {@code dependency-inventory} check and hygiene findings. It is an
 * inventory and hygiene check, not a vulnerability (CVE) scan.</p>
 *
 * @param threats STRIDE threats
 * @param checks security checks
 * @param findings findings with severity
 * @param complianceNotes compliance notes
 * @param passed true when there is no HIGH or CRITICAL finding
 * @param dependencies declared dependencies and build plugins of the workspace (empty when no workspace was
 *                     inventoried, and in reviews recorded before the field existed)
 */
public record SecurityReview(List<Threat> threats, List<Check> checks, List<Finding> findings,
                             List<String> complianceNotes, boolean passed, List<DependencyRecord> dependencies)
        implements ArtifactPayload {

    /** Maximum dependency rows rendered by {@link #markdown()}. */
    static final int MAX_DEPENDENCY_ROWS = 100;

    /**
     * Normalizes collections.
     */
    public SecurityReview {
        threats = Immutables.list(threats);
        checks = Immutables.list(checks);
        findings = Immutables.list(findings);
        complianceNotes = Immutables.list(complianceNotes);
        dependencies = Immutables.list(dependencies);
    }

    /**
     * Creates a review without a dependency inventory (the canonical constructor before it existed).
     *
     * @param threats STRIDE threats
     * @param checks security checks
     * @param findings findings
     * @param complianceNotes compliance notes
     * @param passed whether the review passed
     */
    public SecurityReview(List<Threat> threats, List<Check> checks, List<Finding> findings,
                          List<String> complianceNotes, boolean passed) {
        this(threats, checks, findings, complianceNotes, passed, List.of());
    }

    /** STRIDE threat category. */
    public enum StrideCategory {
        /** Impersonating a user or system. */
        SPOOFING,
        /** Modifying data or code. */
        TAMPERING,
        /** Denying an action without the system being able to prove otherwise. */
        REPUDIATION,
        /** Exposing information to unauthorized parties. */
        INFORMATION_DISCLOSURE,
        /** Degrading or denying service. */
        DENIAL_OF_SERVICE,
        /** Gaining capabilities without authorization. */
        ELEVATION_OF_PRIVILEGE
    }

    /** Treatment status of a threat. */
    public enum ThreatStatus {
        /** A control mitigates the threat. */
        MITIGATED,
        /** The residual risk is accepted. */
        ACCEPTED,
        /** The threat is not addressed. */
        OPEN
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
    public record Threat(String id, StrideCategory category, String description, String mitigation,
                         ThreatStatus status) {
        /**
         * Normalizes text.
         */
        public Threat {
            id = Immutables.text(id);
            description = Immutables.text(description);
            mitigation = Immutables.text(mitigation);
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
            p.requireNonNull(t.category(), "threat " + t.id() + " category");
            p.requireNonNull(t.status(), "threat " + t.id() + " status");
        }
        for (Finding f : findings) {
            p.requireNonNull(f.severity(), "finding severity");
            p.requireText(f.title(), "finding title");
        }
        p.require(passed == computePassed(findings), "passed flag must be true exactly when there is no HIGH or "
                + "CRITICAL finding");
        for (DependencyRecord d : dependencies) {
            p.requireText(d.artifactId(), "dependency artifactId");
            p.requireNonNull(d.kind(), "dependency " + d.artifactId() + " kind");
        }
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Security review").field("Result", passed ? "PASSED" : "FAILED");
        md.heading(3, "Threat model (STRIDE)").table(List.of("ID", "Category", "Threat", "Mitigation", "Status"),
                threats.stream().map(t -> List.of(t.id(), String.valueOf(t.category()), t.description(), t.mitigation(),
                        String.valueOf(t.status()))).toList());
        md.heading(3, "Checks").table(List.of("Check", "Result", "Details"),
                checks.stream().map(c -> List.of(c.name(), c.passed() ? "pass" : "FAIL", c.details())).toList());
        md.heading(3, "Findings").table(List.of("Severity", "Finding", "Recommendation"),
                findings.stream().map(f -> List.of(String.valueOf(f.severity()), f.title(), f.recommendation()))
                        .toList());
        md.heading(3, "Compliance notes").bullets(complianceNotes);
        if (!dependencies.isEmpty()) {
            md.heading(3, "Dependency inventory").paragraph(dependencies.size() + " declarations (an inventory and "
                    + "hygiene check of the Maven build files, not a vulnerability scan).");
            md.table(List.of("Module", "Kind", "Coordinate", "Scope", "Section"),
                    dependencies.stream().limit(MAX_DEPENDENCY_ROWS).map(d -> List.of(d.module(),
                            String.valueOf(d.kind()), d.coordinate(), d.scope(), d.section())).toList());
            if (dependencies.size() > MAX_DEPENDENCY_ROWS) {
                md.paragraph("... and " + (dependencies.size() - MAX_DEPENDENCY_ROWS) + " more.");
            }
        }
        return md.render();
    }
}
