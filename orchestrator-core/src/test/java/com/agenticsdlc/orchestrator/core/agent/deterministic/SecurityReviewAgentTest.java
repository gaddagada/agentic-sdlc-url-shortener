package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

class SecurityReviewAgentTest {

    private final RequirementSpec spec = new RequirementsAgent().analyze("Build a URL shortener service: create short "
            + "links, redirect with HTTP 302 and record click analytics with unique visitors.", List.of(), Map.of(),
            null, WorkspaceMode.NONE, 0.5);
    private final ImpactAnalysis impact = new ImpactAnalyzer().proposeGreenfield(spec);
    private final DesignSpec design = new ArchitectureAgent().design(spec, impact);
    private final ChangeSet changeSet = new ImplementationAgent().implement(design, spec, impact, null, List.of());
    private final SecurityReviewAgent agent = new SecurityReviewAgent();

    private DesignSpec withSecurity(List<String> considerations, DesignSpec.DataHandling handling) {
        return new DesignSpec(design.overview(), design.components(), design.apiChanges(), design.dataModelChanges(),
                design.sequenceFlows(), design.nfrStrategies(), considerations, handling,
                design.alternativesConsidered(), design.risk(), design.decisions());
    }

    @Test
    void deterministicDesignPassesWithStrideThreats() {
        SecurityReview review = agent.review(design, changeSet);
        review.validate();
        assertThat(review.passed()).isTrue();
        assertThat(review.checks()).extracting(Check::name).containsExactly("input-validation-in-design",
                "ssrf-open-redirect-guard", "no-raw-pii", "rate-limiting-on-public-create", "no-secrets-in-change-set",
                "mutating-endpoints-auth-note");
        assertThat(review.checks()).allSatisfy(c -> assertThat(c.passed()).isTrue());
        assertThat(review.threats()).extracting(SecurityReview.Threat::category).contains("SPOOFING", "TAMPERING",
                "REPUDIATION", "INFORMATION_DISCLOSURE", "DENIAL_OF_SERVICE", "ELEVATION_OF_PRIVILEGE");
        assertThat(review.findings()).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.LOW));
        assertThat(review.complianceNotes()).anySatisfy(n -> assertThat(n).contains("client IP address"));
    }

    @Test
    void missingControlsProduceHighFindingsAndFailTheReview() {
        DesignSpec weak = withSecurity(List.of("Use HTTPS"),
                new DesignSpec.DataHandling(List.of("client IP address"), "", ""));
        SecurityReview review = agent.review(weak, changeSet);
        review.validate();
        assertThat(review.passed()).isFalse();
        assertThat(review.findings()).extracting(SecurityReview.Finding::severity)
                .contains(Severity.HIGH, Severity.MEDIUM, Severity.LOW);
        assertThat(review.threats()).anySatisfy(t -> assertThat(t.status()).isEqualTo("OPEN"));
        assertThat(review.checks()).filteredOn(c -> !c.passed()).extracting(Check::name).contains(
                "input-validation-in-design", "ssrf-open-redirect-guard", "no-raw-pii", "rate-limiting-on-public-create",
                "mutating-endpoints-auth-note");
    }

    @Test
    void secretsInTheChangeSetAreCritical() {
        ChangeSet leaky = new ChangeSet("HEAD", List.of(new ChangeSet.FileChange("url-shortener/src/main/java/A.java",
                ChangeSet.Operation.CREATE, "java", "a", ChangeSet.ContentKind.FULL, "token = ghp_abcdefghijklmnopqrstuvwxyz",
                List.of())), "./mvnw verify", List.of(), "revert", List.of());
        SecurityReview review = agent.review(design, leaky);
        assertThat(review.passed()).isFalse();
        assertThat(review.findings()).anySatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.CRITICAL));
    }
}
