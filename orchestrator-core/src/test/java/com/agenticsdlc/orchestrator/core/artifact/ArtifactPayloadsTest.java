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
                new RequirementSpec.Requirement("X-1", "t", RequirementSpec.Priority.MUST,
                        RequirementSpec.Category.FUNCTIONAL, false)),
                List.of(new RequirementSpec.AcceptanceCriterion("AC-1", "g", "w", "t", List.of("FR-9"))), null, null,
                new RequirementSpec.AmbiguityReport(1.5, List.of(), List.of()), null);
        assertThatThrownBy(spec::validate).isInstanceOfSatisfying(InvalidArtifactException.class, e -> {
            assertThat(e.problems()).contains("normalizedStatement must not be blank", "classification is required",
                    "functional must not be empty", "preliminaryRisk is required",
                    "ambiguity score must be within 0..1",
                    "acceptance criterion AC-1 traces unknown requirement FR-9");
            assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("NFR-\\d+"))
                    .contains("non-functional requirement X-1 must not have category FUNCTIONAL");
            assertThat(e.getMessage()).startsWith("RequirementSpec is invalid: ");
        });

        VerificationReport lying = new VerificationReport(List.of(Check.fail("no-secrets", "found")),
                new VerificationReport.Traceability(1, 1, List.of()), null, true, List.of());
        assertThatThrownBy(lying::validate).hasMessageContaining("passed flag is inconsistent");

        SecurityReview inconsistent = new SecurityReview(List.of(new SecurityReview.Threat("T-1", null, "d", "m",
                null)), List.of(Check.pass("x", "y")), List.of(new SecurityReview.Finding(Severity.HIGH, "f", "r")),
                List.of(), true);
        assertThatThrownBy(inconsistent::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).hasSize(3).contains("threat T-1 category is required",
                        "threat T-1 status is required"));

        ChangeSet traversal = new ChangeSet("HEAD", List.of(new ChangeSet.FileChange("../etc/passwd",
                ChangeSet.Operation.CREATE, "text", "s", ChangeSet.ContentKind.FULL, "x", List.of())), "", List.of(),
                "", List.of());
        assertThatThrownBy(traversal::validate).hasMessageContaining("relative path").hasMessageContaining("buildCommand");

        ReleaseReadiness conditional = new ReleaseReadiness(List.of(new ReleaseReadiness.ChecklistItem("i", null,
                "e")), ReleaseReadiness.GoNoGo.GO_WITH_CONDITIONS, List.of(), null, "");
        assertThatThrownBy(conditional::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).hasSize(3).contains("checklist item 'i' status is required"));

        ImpactAnalysis untyped = new ImpactAnalysis("ws", 1, true, List.of(new ImpactAnalysis.ImpactedComponent("A",
                null, "a/A.java", 0.5, List.of(), ImpactAnalysis.ChangeNature.MODIFY)), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "summary");
        assertThatThrownBy(untyped::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).containsExactly("component A type is required"));

        TestPlan dangling = new TestPlan("s", List.of(new TestPlan.TestLevel(TestPlan.Level.UNIT, "", "")),
                List.of(new TestPlan.TestCase("TC-1", "t", TestPlan.Level.UNIT, TestPlan.CaseType.POSITIVE, "",
                        List.of(), "", List.of())), Map.of("FR-1", "TC-1,TC-9"), List.of());
        assertThatThrownBy(dangling::validate).hasMessageContaining("TC-9");
    }

    @Test
    void enumFieldsRenderTheirConstantNamesInMarkdown() {
        RequirementSpec base = Payloads.requirementSpec(RiskLevel.LOW);
        RequirementSpec withNfr = new RequirementSpec(base.normalizedStatement(), base.intent(), base.classification(),
                base.scopeIn(), base.scopeOut(), base.functional(), List.of(new RequirementSpec.Requirement("NFR-1",
                "p95 under 50 ms", RequirementSpec.Priority.SHOULD, RequirementSpec.Category.PERFORMANCE, true)),
                base.acceptanceCriteria(), base.assumptions(), base.openQuestions(), base.ambiguity(),
                base.preliminaryRisk());
        withNfr.validate();
        assertThat(withNfr.markdown()).contains("| NFR-1 | PERFORMANCE | SHOULD | p95 under 50 ms | yes |");

        ImpactAnalysis impact = new ImpactAnalysis("ws", 3, true, List.of(new ImpactAnalysis.ImpactedComponent(
                "LinkController", ImpactAnalysis.ComponentType.CONTROLLER, "api/LinkController.java", 0.9,
                List.of("endpoint"), ImpactAnalysis.ChangeNature.MODIFY)), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "summary");
        impact.validate();
        assertThat(impact.markdown()).contains("| LinkController | CONTROLLER | MODIFY | 0.90 |");

        SecurityReview review = new SecurityReview(List.of(new SecurityReview.Threat("T-1",
                SecurityReview.StrideCategory.DENIAL_OF_SERVICE, "floods", "rate limiting",
                SecurityReview.ThreatStatus.MITIGATED)), List.of(Check.pass("x", "y")), List.of(), List.of(), true);
        review.validate();
        assertThat(review.markdown()).contains("| T-1 | DENIAL_OF_SERVICE | floods | rate limiting | MITIGATED |");

        ReleaseReadiness release = Payloads.release(ReleaseReadiness.GoNoGo.GO);
        assertThat(release.markdown()).contains("| Verification passed | PASS | ok |");
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
