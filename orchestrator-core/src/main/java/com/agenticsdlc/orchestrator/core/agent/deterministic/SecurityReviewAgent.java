package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview.Finding;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview.StrideCategory;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview.Threat;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview.ThreatStatus;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;

/**
 * Deterministic security reviewer ({@code security-scan}): STRIDE threats templated from the API changes and
 * data handling, and checks for input validation, SSRF/open-redirect guard, raw PII, rate limiting of
 * public create endpoints, secrets in the change set and an authentication note for mutating endpoints.
 * {@code passed} is true when there is no HIGH or CRITICAL finding; a failed review is still a successful
 * stage (the release stage turns it into NO_GO).
 */
public final class SecurityReviewAgent implements StageAgent {

    /**
     * Creates the agent.
     */
    public SecurityReviewAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.SECURITY_REVIEW;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        SecurityReview review = review(context.require(DesignSpec.class), context.require(ChangeSet.class));
        return StageResult.success(review, (review.passed() ? "passed" : "failed") + " with "
                + review.findings().size() + " findings and " + review.threats().size() + " threats");
    }

    /**
     * Reviews the design and change set.
     *
     * @param design design
     * @param changeSet change set
     * @return the review
     */
    public SecurityReview review(DesignSpec design, ChangeSet changeSet) {
        String considerations = String.join(" ", design.securityConsiderations()).toLowerCase(Locale.ROOT);
        boolean handlesUrls = design.apiChanges().stream().anyMatch(a -> a.path().contains("link")
                || a.path().startsWith("/{")) || considerations.contains("url");
        boolean mutating = design.apiChanges().stream().anyMatch(a -> !a.method().equals("GET"));
        boolean publicCreate = design.apiChanges().stream().anyMatch(a -> a.method().equals("POST"));
        boolean validation = considerations.contains("validat");
        boolean ssrfGuard = considerations.contains("ssrf") || considerations.contains("private");
        boolean rateLimit = considerations.contains("rate limit");
        boolean authNote = considerations.contains("auth");
        DesignSpec.DataHandling data = design.dataHandling();
        boolean pii = data != null && !data.piiFields().isEmpty();
        boolean piiProtected = !pii || !data.protection().isBlank() && !data.protection().startsWith("not applicable");
        List<String> secretFiles = changeSet.changes().stream()
                .filter(c -> SecretPatterns.containsSecret(c.content())).map(ChangeSet.FileChange::path).toList();

        List<Threat> threats = new ArrayList<>();
        if (mutating) {
            threats.add(new Threat("T-1", StrideCategory.SPOOFING, "Unauthenticated clients call mutating endpoints",
                    "Authentication (API key or OIDC) in production; per-client rate limiting",
                    authNote ? ThreatStatus.ACCEPTED : ThreatStatus.OPEN));
        }
        threats.add(new Threat("T-" + (threats.size() + 1), StrideCategory.TAMPERING, "Malformed or oversized input",
                "Input validation and length limits", mitigated(validation)));
        threats.add(new Threat("T-" + (threats.size() + 1), StrideCategory.REPUDIATION, "Changes cannot be attributed",
                "Audit trail of governance actions and structured request logs", ThreatStatus.ACCEPTED));
        if (pii) {
            threats.add(new Threat("T-" + (threats.size() + 1), StrideCategory.INFORMATION_DISCLOSURE,
                    "Exposure of visitor data (" + String.join(", ", data.piiFields()) + ")",
                    data.protection().isBlank() ? "none defined" : data.protection(),
                    mitigated(piiProtected)));
        }
        if (publicCreate || handlesUrls) {
            threats.add(new Threat("T-" + (threats.size() + 1), StrideCategory.DENIAL_OF_SERVICE, "Request floods on "
                    + "public endpoints", "Token bucket rate limiting and caching", mitigated(rateLimit)));
        }
        if (handlesUrls) {
            threats.add(new Threat("T-" + (threats.size() + 1), StrideCategory.ELEVATION_OF_PRIVILEGE,
                    "SSRF or open redirect through user-supplied URLs reaching internal networks",
                    "Block loopback, private, link-local and metadata targets; scheme allow-list",
                    mitigated(ssrfGuard)));
        }

        List<Check> checks = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        check(checks, findings, "input-validation-in-design", validation, Severity.HIGH,
                "Input validation is not part of the design",
                "Add validation of every external input (scheme, length, format) to the design");
        if (handlesUrls) {
            check(checks, findings, "ssrf-open-redirect-guard", ssrfGuard, Severity.HIGH,
                    "No SSRF or open-redirect guard for user-supplied URLs",
                    "Block private, loopback, link-local and metadata destinations");
        }
        check(checks, findings, "no-raw-pii", piiProtected, Severity.HIGH,
                "Personal data is processed without protection",
                "Hash or encrypt personal data and define a retention period");
        if (publicCreate) {
            check(checks, findings, "rate-limiting-on-public-create", rateLimit, Severity.MEDIUM,
                    "Public create endpoints are not rate limited", "Apply the per-client token bucket");
        }
        check(checks, findings, "no-secrets-in-change-set", secretFiles.isEmpty(), Severity.CRITICAL,
                "Credential patterns in " + String.join(", ", secretFiles),
                "Remove the credentials and rotate them");
        if (mutating) {
            check(checks, findings, "mutating-endpoints-auth-note", authNote, Severity.LOW,
                    "Mutating endpoints have no authentication note", "Document the authentication model");
            if (authNote) {
                findings.add(new Finding(Severity.LOW, "Prototype has no authentication on mutating endpoints",
                        "Introduce API keys or OIDC before production exposure"));
            }
        }
        List<String> compliance = new ArrayList<>();
        if (pii) {
            compliance.add("Personal data: " + String.join(", ", data.piiFields()) + "; retention "
                    + data.retention() + "; protection " + data.protection());
        } else {
            compliance.add("No personal data is processed by this change");
        }
        compliance.add("Audit trail: every governance decision is recorded with the operator identity");
        return new SecurityReview(threats, checks, findings, compliance, SecurityReview.computePassed(findings));
    }

    private static void check(List<Check> checks, List<Finding> findings, String name, boolean ok, Severity severity,
                              String problem, String recommendation) {
        checks.add(ok ? Check.pass(name, "satisfied") : Check.fail(name, problem));
        if (!ok) {
            findings.add(new Finding(severity, problem, recommendation));
        }
    }

    private static ThreatStatus mitigated(boolean control) {
        return control ? ThreatStatus.MITIGATED : ThreatStatus.OPEN;
    }
}
