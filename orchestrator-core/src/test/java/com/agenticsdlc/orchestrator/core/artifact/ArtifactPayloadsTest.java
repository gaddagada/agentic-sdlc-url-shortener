package com.agenticsdlc.orchestrator.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
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

    private static TestPlan planWith(Map<String, String> matrix, List<String> risksNotCovered, String... caseIds) {
        return new TestPlan("s", List.of(new TestPlan.TestLevel(TestPlan.Level.UNIT, "", "")),
                java.util.Arrays.stream(caseIds).map(id -> new TestPlan.TestCase(id, "t", TestPlan.Level.UNIT,
                        TestPlan.CaseType.POSITIVE, "", List.of(), "", List.of())).toList(), matrix, risksNotCovered);
    }

    @Test
    void coverageMatrixValuesTolerateProseAndAreNormalizedToCaseIds() {
        // the exact value a live Opus run produced (it failed validation before)
        String prose = "TC-20 (automated presence and content checks); the qualitative doc review is part of the "
                + "T14/T15 human review";
        TestPlan plan = planWith(Map.of("NFR-6", prose, "FR-1", "TC-1,TC-4", "FR-2", " TC-4 , TC-1, TC-4 "), List.of(),
                "TC-1", "TC-4", "TC-20");
        plan.validate();
        assertThat(plan.coverageMatrix()).containsEntry("NFR-6", "TC-20").containsEntry("FR-1", "TC-1, TC-4")
                .containsEntry("FR-2", "TC-4, TC-1");
        assertThat(TestPlan.caseIds(prose)).containsExactly("TC-20");
        assertThat(plan.markdown()).contains("| NFR-6 | TC-20 |");
        assertThat(JsonCodec.standard().canonicalJson(plan)).as("canonical and therefore stable hashes")
                .isEqualTo(JsonCodec.standard().canonicalJson(planWith(Map.of("NFR-6", "TC-20", "FR-1",
                        "TC-1, TC-4", "FR-2", "TC-4, TC-1"), List.of(), "TC-1", "TC-4", "TC-20")));
        TestPlan roundTrip = JsonCodec.standard().fromJson(JsonCodec.standard().toJson(plan), TestPlan.class);
        assertThat(roundTrip).isEqualTo(plan);

        TestPlan unknown = planWith(Map.of("NFR-6", prose), List.of(), "TC-1");
        assertThatThrownBy(unknown::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).containsExactly("coverageMatrix entry NFR-6 references unknown test case "
                        + "TC-20"));

        TestPlan noCase = planWith(Map.of("NFR-6", "manual review only"), List.of(), "TC-1");
        assertThatThrownBy(noCase::validate).isInstanceOfSatisfying(InvalidArtifactException.class,
                e -> assertThat(e.problems()).singleElement().asString()
                        .startsWith("coverageMatrix entry NFR-6 references no test case"));
        planWith(Map.of("NFR-6", ""), List.of("NFR-6 (USABILITY) has no automated test; manual review required"),
                "TC-1").validate();
        assertThatThrownBy(planWith(Map.of("FR-1", ""), List.of("FR-10 is manual"), "TC-1")::validate)
                .as("FR-10 is not FR-1").isInstanceOf(InvalidArtifactException.class);
    }

    @Test
    void verificationReportsReadOlderJsonWithoutTheNewFields() {
        VerificationReport legacy = Payloads.verification(true);
        assertThat(legacy.skeletonOnly()).isFalse();
        assertThat(legacy.notes()).isEmpty();
        String json = "{\"build\":null,\"feedbackForImplementation\":[],\"passed\":true,\"structuralChecks\":"
                + "[{\"details\":\"ok\",\"name\":\"x\",\"passed\":true}],\"traceability\":{\"requirementsCovered\":1,"
                + "\"requirementsTotal\":1,\"uncovered\":[]}}";
        VerificationReport read = JsonCodec.standard().fromJson(json, VerificationReport.class);
        assertThat(read.skeletonOnly()).isFalse();
        assertThat(read.notes()).isEmpty();
        read.validate();
        VerificationReport flagged = new VerificationReport(legacy.structuralChecks(), legacy.traceability(), null,
                true, List.of(), true, List.of(VerificationReport.NO_BUILD_NOTE));
        assertThat(JsonCodec.standard().fromJson(JsonCodec.standard().toJson(flagged), VerificationReport.class))
                .isEqualTo(flagged);
        assertThat(flagged.markdown()).contains("skeleton change plan").contains(VerificationReport.NO_BUILD_NOTE);
    }

    @Test
    void contextSummariesAreCompactAndTheChangeSetSummaryOmitsFileContents() {
        String body = "class Feature {\n" + "    // line\n".repeat(400) + "}\n";
        ChangeSet changeSet = Payloads.changeSet("url-shortener/src/main/java/com/example/Feature.java", body);
        String summary = changeSet.contextSummary(1_500);
        assertThat(summary).contains("url-shortener/src/main/java/com/example/Feature.java").contains("CREATE")
                .doesNotContain("// line").doesNotContain("class Feature {");
        assertThat(changeSet.markdown()).contains("// line");

        String markdown = "## Title\n\n" + "- a bullet with some words\n".repeat(200);
        assertThat(Markdown.truncate(markdown, 1_500)).hasSizeLessThanOrEqualTo(1_500 + "\n[truncated]\n".length())
                .endsWith("\n[truncated]\n").startsWith("## Title");
        assertThat(Markdown.truncate("short", 1_500)).isEqualTo("short");
        DesignSpec design = Payloads.design(RiskLevel.MEDIUM);
        assertThat(design.contextSummary(100_000)).isEqualTo(design.markdown());
        assertThat(design.contextSummary(40)).endsWith("[truncated]\n");
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
