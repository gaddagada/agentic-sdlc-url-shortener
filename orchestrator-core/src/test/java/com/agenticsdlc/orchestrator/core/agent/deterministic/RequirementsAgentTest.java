package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AmbiguityFinding;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Classification;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;

class RequirementsAgentTest {

    private final RequirementsAgent agent = new RequirementsAgent();

    private RequirementSpec analyze(String requirement) {
        return agent.analyze(requirement, List.of(), Map.of(), null, WorkspaceMode.NONE, 0.5);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            Make the shortener faster and more reliable.                                     | 0.90 | SHORT_STATEMENT,VAGUE_QUALIFIER_WITHOUT_METRIC,NO_MEASURABLE_TARGET,NO_DOMAIN_NOUN
            Improve it somehow, etc.                                                         | 1.00 | SHORT_STATEMENT,VAGUE_QUALIFIER_WITHOUT_METRIC,NO_MEASURABLE_TARGET,NO_DOMAIN_NOUN,OPEN_ENDED_PHRASE
            The redirect endpoint must answer within 50 ms at 500 rps for every short link.   | 0.00 |
            Add an API to export link analytics as CSV for every link the user owns today.    | 0.15 | NO_MEASURABLE_TARGET
            Rewrite everything in the entire system so it is cleaner and scalable for the link API.| 0.55 | VAGUE_QUALIFIER_WITHOUT_METRIC,NO_MEASURABLE_TARGET,UNSPECIFIED_SCOPE
            Use no database but persist every link and click durably for one year at most 99%.| 0.20 | CONFLICTING_TERMS
            Process redirects synchronous but record clicks asynchronously within 5 seconds always.| 0.20 | CONFLICTING_TERMS
            """)
    void scoresAmbiguity(String requirement, double score, String codes) {
        RequirementSpec.AmbiguityReport report = RequirementsAgent.ambiguity(requirement);
        assertThat(report.score()).isEqualTo(score);
        List<String> expected = codes == null ? List.of() : List.of(codes.split(","));
        assertThat(report.findings()).extracting(AmbiguityFinding::code).containsExactlyElementsOf(expected);
        assertThat(report.questions()).hasSameSizeAs(report.findings());
        assertThat(report.questions()).extracting(ClarificationQuestion::id)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, expected.size())
                        .mapToObj(i -> "q" + i).toList());
    }

    @Test
    void vagueQualifierQuestionAsksForLatencyTargets() {
        RequirementSpec.AmbiguityReport report = RequirementsAgent.ambiguity("Make the shortener faster.");
        assertThat(report.questions()).anySatisfy(q -> assertThat(q.question()).isEqualTo(
                "Which operations must be faster (redirect, create, analytics) and what is the target p95 latency?"));
    }

    @Test
    void classifiesGreenfieldBrownfieldAndAmbiguous() {
        assertThat(analyze("Build a URL shortener service: create short links and redirect with HTTP 302.")
                .classification()).isEqualTo(Classification.GREENFIELD);
        assertThat(analyze("Add a bulk link creation API to the existing URL shortener with up to 100 URLs.")
                .classification()).isEqualTo(Classification.BROWNFIELD);
        assertThat(analyze("Make the shortener faster and more reliable.").classification())
                .isEqualTo(Classification.AMBIGUOUS);
        com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex index =
                new com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex("ws", null, java.time.Instant.EPOCH,
                        List.of(new com.agenticsdlc.orchestrator.core.workspace.IndexedType("a.Link", "Link",
                                "m/src/main/java/a/Link.java", "m",
                                com.agenticsdlc.orchestrator.core.workspace.TypeKind.ENTITY, List.of(), "links",
                                java.util.Set.of(), false)), List.of(), List.of(), Map.of());
        assertThat(agent.analyze("Support QR codes for links with a PNG endpoint of 256 pixels.", List.of(), Map.of(),
                index, WorkspaceMode.REPO, 0.5).classification()).isEqualTo(Classification.BROWNFIELD);
        assertThat(agent.analyze("Build a new QR code service for links with 256 pixel images.", List.of(), Map.of(),
                index, WorkspaceMode.REPO, 0.5).classification()).isEqualTo(Classification.GREENFIELD);
    }

    @Test
    void extractsRequirementsCategoriesAndAcceptanceCriteria() {
        RequirementSpec spec = analyze("Build a URL shortener service: create short links for HTTP and HTTPS URLs "
                + "with optional expiry, redirect with HTTP 302, record click analytics (total clicks, clicks per day, "
                + "top referrers) and expose health checks. The p95 redirect latency must be under 50 ms at 500 "
                + "requests per second. The service must prevent redirects to private networks.");
        assertThat(spec.functional()).extracting(RequirementSpec.Requirement::text).containsExactly(
                "Create short links for HTTP and HTTPS URLs with optional expiry", "Redirect with HTTP 302",
                "Record click analytics (total clicks, clicks per day, top referrers)");
        assertThat(spec.nonFunctional()).extracting(r -> r.id() + ":" + r.category() + ":" + r.priority())
                .containsExactly("NFR-1:OPERABILITY:MUST", "NFR-2:PERFORMANCE:MUST", "NFR-3:SECURITY:MUST");
        assertThat(spec.nonFunctional().get(1).measurable()).isTrue();
        assertThat(spec.acceptanceCriteria()).hasSize(4);
        assertThat(spec.acceptanceCriteria().getFirst().then()).contains("created");
        assertThat(spec.acceptanceCriteria().getLast().traces()).containsExactly("NFR-2");
        assertThat(spec.preliminaryRisk().level()).isEqualTo(RiskLevel.HIGH);
        assertThat(spec.scopeIn()).contains("Redirect handling", "Click analytics");
        assertThat(spec.intent()).isEqualTo("Build a URL shortener service");
        spec.validate();
    }

    @Test
    void createsAFallbackRequirementAndLowRiskWhenNothingIsExtracted() {
        RequirementSpec spec = analyze("Links default to no expiry for the redirect service with 3 retries.");
        assertThat(spec.functional()).hasSize(1);
        RequirementSpec trivial = analyze("Colour the documentation pages blue for the 2026 release of the docs.");
        assertThat(trivial.preliminaryRisk().level()).isEqualTo(RiskLevel.LOW);
        assertThat(trivial.functional()).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo("FR-1"));
    }

    @Test
    void asksForClarificationWhenAmbiguousAndNoAnswersExist() {
        StageResult result = agent.execute(Contexts.of(AgentRole.REQUIREMENTS,
                "Make the shortener faster and more reliable."));
        assertThat(result).isInstanceOfSatisfying(StageResult.NeedsClarification.class,
                c -> assertThat(c.questions()).hasSize(4));
    }

    @Test
    void mergesClarificationAnswersIntoTheStatement() {
        String requirement = "Make the shortener faster and more reliable.";
        List<ClarificationQuestion> questions = RequirementsAgent.ambiguity(requirement).questions();
        Map<String, String> answers = new LinkedHashMap<>();
        questions.forEach(q -> answers.put(q.id(), q.suggestedAnswers().getFirst()));
        StageResult result = agent.execute(Contexts.withAnswers(requirement, questions, answers));
        assertThat(result).isInstanceOfSatisfying(StageResult.Success.class, s -> {
            RequirementSpec spec = (RequirementSpec) s.payload();
            assertThat(spec.normalizedStatement()).startsWith(requirement + " Clarifications: ")
                    .contains("Redirect p95 under 50 ms at 500 rps");
            assertThat(spec.classification()).isEqualTo(Classification.BROWNFIELD);
            assertThat(spec.assumptions()).contains("Clarification answers are authoritative");
            assertThat(spec.openQuestions()).isEmpty();
            assertThat(spec.allRequirements()).anySatisfy(r -> assertThat(r.text())
                    .isEqualTo("Redirect p95 under 50 ms at 500 rps"));
        });
    }

    @Test
    void splitsClausesButNotInsideParentheses() {
        assertThat(RequirementsAgent.splitClauses(" create links, record clicks (total, per day) and expose health"))
                .containsExactly("create links", "record clicks (total, per day)", "expose health");
    }
}
