package com.agenticsdlc.orchestrator.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class ArtifactPayloadsTest {

    @Test
    void minimalPayloadsAreValidAndRenderMarkdown() {
        for (AgentRole role : AgentRole.values()) {
            ArtifactPayload payload = Payloads.forRole(role);
            payload.validate();
            assertThat(payload.kind()).isEqualTo(role.artifactKind());
            assertThat(payload.markdown()).startsWith("## ").endsWith("\n");
        }
    }

    @Test
    void invalidPayloadsListEveryProblem() {
        RequirementSpec spec = new RequirementSpec("", "", null, null, null, List.of(), List.of(
                new RequirementSpec.Requirement("X-1", "t", RequirementSpec.Priority.MUST, "WEIRD", false)),
                List.of(new RequirementSpec.AcceptanceCriterion("AC-1", "g", "w", "t", List.of("FR-9"))), null, null,
                new RequirementSpec.AmbiguityReport(1.5, List.of(), List.of()), null);
        assertThatThrownBy(spec::validate).isInstanceOfSatisfying(InvalidArtifactException.class, e -> {
            assertThat(e.problems()).contains("normalizedStatement must not be blank", "classification is required",
                    "functional must not be empty", "preliminaryRisk is required",
                    "ambiguity score must be within 0..1",
                    "acceptance criterion AC-1 traces unknown requirement FR-9");
            assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("NFR-\\d+"))
                    .anySatisfy(p -> assertThat(p).contains("WEIRD"));
            assertThat(e.getMessage()).startsWith("RequirementSpec is invalid: ");
        });

        VerificationReport lying = new VerificationReport(List.of(Check.fail("no-secrets", "found")),
                new VerificationReport.Traceability(1, 1, List.of()), null, true, List.of());
        assertThatThrownBy(lying::validate).hasMessageContaining("passed flag is inconsistent");

        SecurityReview inconsistent = new SecurityReview(List.of(new SecurityReview.Threat("T-1", "MAGIC", "d", "m",
                "UNKNOWN")), List.of(Check.pass("x", "y")), List.of(new SecurityReview.Finding(Severity.HIGH, "f", "r")),
                List.of(), true);
        assertThatThrownBy(inconsistent::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).hasSize(3));

        ChangeSet traversal = new ChangeSet("HEAD", List.of(new ChangeSet.FileChange("../etc/passwd",
                ChangeSet.Operation.CREATE, "text", "s", ChangeSet.ContentKind.FULL, "x", List.of())), "", List.of(),
                "", List.of());
        assertThatThrownBy(traversal::validate).hasMessageContaining("relative path").hasMessageContaining("buildCommand");

        ReleaseReadiness conditional = new ReleaseReadiness(List.of(new ReleaseReadiness.ChecklistItem("i", "MAYBE",
                "e")), ReleaseReadiness.GoNoGo.GO_WITH_CONDITIONS, List.of(), null, "");
        assertThatThrownBy(conditional::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).hasSize(3));

        TestPlan dangling = new TestPlan("s", List.of(new TestPlan.TestLevel(TestPlan.Level.UNIT, "", "")),
                List.of(new TestPlan.TestCase("TC-1", "t", TestPlan.Level.UNIT, TestPlan.CaseType.POSITIVE, "",
                        List.of(), "", List.of())), Map.of("FR-1", "TC-1,TC-9"), List.of());
        assertThatThrownBy(dangling::validate).hasMessageContaining("TC-9");
    }

    @Test
    void markdownTablesEscapeUntrustedText() {
        String markdown = new Markdown().table(List.of("A", "B"), List.of(List.of("x | y", "line1\nline2"),
                List.of("", "z"))).render();
        assertThat(markdown).contains("| x \\| y | line1 line2 |").contains("| - | z |");
        assertThat(new Markdown().bullets(List.of()).render()).isEqualTo("- _none_\n");
        assertThat(new Markdown().code("java", "a ``` b").render()).startsWith("~~~~java");
        assertThat(Markdown.join(List.of())).isEqualTo("-");
    }

    @Test
    void riskAssessmentsMergeByMaximumAndUnion() {
        RiskAssessment merged = new RiskAssessment(RiskLevel.MEDIUM, List.of("a", "b"))
                .merge(new RiskAssessment(RiskLevel.HIGH, List.of("b", "c")));
        assertThat(merged.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(merged.factors()).containsExactly("a", "b", "c");
        assertThat(RiskLevel.max(RiskLevel.LOW, RiskLevel.CRITICAL)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(Severity.HIGH.atLeast(Severity.MEDIUM)).isTrue();
    }
}
