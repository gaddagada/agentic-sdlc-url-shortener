package com.agenticsdlc.orchestrator.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.deterministic.ArchitectureAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DocumentationAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.ImplementationAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.PlanningAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.ReleaseAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.RequirementsAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.SecurityReviewAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.TestDesignAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.VerificationAgent;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

class ArtifactScorersTest {

    static final String REQUIREMENT = "Build a URL shortener service: create short links for HTTP and HTTPS URLs with "
            + "optional expiry, redirect with HTTP 302, record click analytics (total clicks, clicks per day, top "
            + "referrers) and expose health checks. The p95 redirect latency must be under 50 ms at 500 requests "
            + "per second. Links default to no expiry. The service must prevent redirects to private networks.";

    static RequirementSpec spec;
    static TaskPlan plan;
    static ImpactAnalysis impact;
    static DesignSpec design;
    static ChangeSet changeSet;
    static TestPlan testPlan;
    static VerificationReport verification;
    static DocumentationBundle docs;
    static SecurityReview security;
    static ReleaseReadiness release;
    static ScoringContext good;

    @TempDir
    static Path dir;
    static CodebaseIndex index;

    @BeforeAll
    static void generateGoodArtifacts() {
        spec = new RequirementsAgent().analyze(REQUIREMENT, List.of(), Map.of(), null, WorkspaceMode.NONE, 0.5);
        plan = new PlanningAgent().plan(spec);
        impact = new ImpactAnalyzer().proposeGreenfield(spec);
        design = new ArchitectureAgent().design(spec, impact);
        changeSet = new ImplementationAgent().implement(design, spec, impact, null, List.of());
        testPlan = new TestDesignAgent().plan(spec, design);
        verification = new VerificationAgent().verify(changeSet, design, testPlan, spec, null);
        docs = new DocumentationAgent().document(spec, design, changeSet);
        security = new SecurityReviewAgent().review(design, changeSet);
        release = new ReleaseAgent().assess(spec, design, changeSet, verification, security, docs,
                AutonomyLevel.GUIDED, true, List.of("a1"));
        good = context(WorkspaceMode.NONE, null, spec, plan, impact, design, changeSet, testPlan, verification, docs,
                security, release);
    }

    @BeforeAll
    static void indexWorkspace() {
        index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir.resolve("ws")));
    }

    static ScoringContext context(WorkspaceMode mode, CodebaseIndex codebase, ArtifactPayload... payloads) {
        Map<ArtifactKind, ArtifactPayload> map = new EnumMap<>(ArtifactKind.class);
        for (ArtifactPayload payload : payloads) {
            map.put(payload.kind(), payload);
        }
        return new ScoringContext(map, codebase, mode, AutonomyLevel.GUIDED);
    }

    static Score score(ArtifactPayload payload, ScoringContext context) {
        return ArtifactScorers.forKind(payload.kind()).scoreAny(payload, context);
    }

    static double criterion(Score score, String name) {
        return score.criterion(name).orElseThrow(() -> new AssertionError("no criterion " + name)).value();
    }

    @Test
    void everyKindHasAScorerAndScoresAreWeightedMeans() {
        assertThat(ArtifactScorers.scorers()).containsOnlyKeys(ArtifactKind.values());
        ArtifactScorers.scorers().forEach((kind, scorer) -> assertThat(scorer.kind()).isEqualTo(kind));
        Score score = Score.of(List.of(new Criterion("a", 2, 1.0, ""), new Criterion("b", 1, 0.25, ""),
                new Criterion("n/a", 0, 0.0, "not applicable")));
        assertThat(score.value()).isCloseTo(2.25 / 3, within(1e-12));
        assertThat(Score.of(List.of()).value()).isZero();
        assertThat(new Criterion("c", 1, 7.0, "").value()).as("clamped").isEqualTo(1.0);
        assertThatThrownBy(() -> new Criterion(" ", 1, 0, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Criterion("c", -1, 0, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Criterion("c", 1, Double.NaN, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactScorers.forKind(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void requirementSpecRewardsAcceptanceCriteriaMeasurableNfrsAndResolvedAmbiguity() {
        Score goodScore = score(spec, good);
        assertThat(criterion(goodScore, "acceptance-criteria-per-fr")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "ambiguity-resolved")).isEqualTo(1.0);
        RequirementSpec poor = new RequirementSpec("Make it better.", "Make it better",
                RequirementSpec.Classification.AMBIGUOUS, List.of(), List.of(), List.of(
                new RequirementSpec.Requirement("FR-1", "a", RequirementSpec.Priority.MUST,
                        RequirementSpec.Category.FUNCTIONAL, false),
                new RequirementSpec.Requirement("FR-2", "b", RequirementSpec.Priority.MUST,
                        RequirementSpec.Category.FUNCTIONAL, false)),
                List.of(new RequirementSpec.Requirement("NFR-1", "fast", RequirementSpec.Priority.SHOULD,
                        RequirementSpec.Category.PERFORMANCE, false)),
                List.of(new RequirementSpec.AcceptanceCriterion("AC-1", "g", "w", "t", List.of("FR-1"))), List.of(),
                List.of("which?"), new RequirementSpec.AmbiguityReport(0.8, List.of(), List.of()),
                new RiskAssessment(RiskLevel.HIGH, List.of()));
        Score poorScore = score(poor, ScoringContext.empty());
        assertThat(criterion(poorScore, "acceptance-criteria-per-fr")).isEqualTo(0.5);
        assertThat(criterion(poorScore, "measurable-nfrs")).isZero();
        assertThat(criterion(poorScore, "ambiguity-resolved")).isZero();
        assertThat(criterion(poorScore, "risk-assessed")).isZero();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }

    @Test
    void taskPlanRewardsCoverageOrderAndImpactFirstInBrownfield() {
        Score goodScore = score(plan, good);
        assertThat(criterion(goodScore, "fr-coverage")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "dependency-order")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "human-release-gate")).isEqualTo(1.0);
        assertThat(goodScore.criterion("impact-first-in-brownfield").orElseThrow().applies()).isFalse();

        TaskPlan poor = new TaskPlan(List.of(
                new TaskPlan.Task("T1", "Implement", "", TaskPlan.TaskType.IMPLEMENT, List.of("T2"),
                        TaskPlan.Estimate.M, TaskPlan.Owner.AGENT, List.of(), "done"),
                new TaskPlan.Task("T2", "Design", "", TaskPlan.TaskType.DESIGN, List.of(), TaskPlan.Estimate.S,
                        TaskPlan.Owner.AGENT, List.of(), "done")), List.of(), List.of(), "");
        RequirementSpec brownfield = withClassification(spec, RequirementSpec.Classification.BROWNFIELD);
        Score poorScore = score(poor, context(WorkspaceMode.REPO, null, brownfield));
        assertThat(criterion(poorScore, "fr-coverage")).isZero();
        assertThat(criterion(poorScore, "dependency-order")).isEqualTo(0.5);
        assertThat(poorScore.criterion("impact-first-in-brownfield").orElseThrow()).satisfies(c -> {
            assertThat(c.applies()).isTrue();
            assertThat(c.value()).isZero();
        });
        assertThat(criterion(poorScore, "human-release-gate")).isZero();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());

        TaskPlan brownfieldPlan = new PlanningAgent().plan(brownfield);
        assertThat(criterion(score(brownfieldPlan, context(WorkspaceMode.REPO, null, brownfield)),
                "impact-first-in-brownfield")).isEqualTo(1.0);
    }

    static RequirementSpec withClassification(RequirementSpec s, RequirementSpec.Classification classification) {
        return new RequirementSpec(s.normalizedStatement(), s.intent(), classification, s.scopeIn(), s.scopeOut(),
                s.functional(), s.nonFunctional(), s.acceptanceCriteria(), s.assumptions(), s.openQuestions(),
                s.ambiguity(), s.preliminaryRisk());
    }

    @Test
    void impactAnalysisMustMatchTheWorkspaceAndNameIndexedPaths() {
        assertThat(criterion(score(impact, good), "consistent-with-workspace")).isEqualTo(1.0);
        ScoringContext repo = context(WorkspaceMode.REPO, index, spec);
        assertThat(criterion(score(impact, repo), "consistent-with-workspace")).as("greenfield proposal for a repo")
                .isZero();

        ImpactAnalysis brownfield = new ImpactAnalyzer().analyze(index, withClassification(spec,
                RequirementSpec.Classification.BROWNFIELD));
        Score real = score(brownfield, repo);
        assertThat(criterion(real, "consistent-with-workspace")).isEqualTo(1.0);
        assertThat(criterion(real, "paths-known")).isEqualTo(1.0);
        ImpactAnalysis invented = new ImpactAnalysis("ws", 10, true, List.of(new ImpactAnalysis.ImpactedComponent(
                "GhostService", ImpactAnalysis.ComponentType.SERVICE, "url-shortener/src/main/java/Ghost.java", 0.4,
                List.of(), ImpactAnalysis.ChangeNature.MODIFY)), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), "invented");
        Score inventedScore = score(invented, repo);
        assertThat(criterion(inventedScore, "paths-known")).isZero();
        assertThat(criterion(inventedScore, "reasons-given")).isZero();
        assertThat(real.value()).isGreaterThan(inventedScore.value());
    }

    @Test
    void designRewardsExplainedRiskDecisionsNfrStrategiesAndKnownTypes() {
        Score goodScore = score(design, good);
        assertThat(criterion(goodScore, "risk-assessed-with-factors")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "decisions-recorded")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "nfr-strategies")).isEqualTo(1.0);
        DesignSpec poor = new DesignSpec("o", List.of(new DesignSpec.Component("LinkService", "r", "SERVICE", false),
                new DesignSpec.Component("GhostService", "r", "SERVICE", false)), List.of(), List.of(), List.of(),
                List.of(), List.of(), new DesignSpec.DataHandling(List.of("client IP"), "", ""), List.of(),
                new RiskAssessment(RiskLevel.HIGH, List.of()), List.of());
        Score poorScore = score(poor, context(WorkspaceMode.REPO, index, spec));
        assertThat(criterion(poorScore, "risk-assessed-with-factors")).isZero();
        assertThat(criterion(poorScore, "decisions-recorded")).isZero();
        assertThat(criterion(poorScore, "nfr-strategies")).isZero();
        assertThat(criterion(poorScore, "known-types-in-repo")).isEqualTo(0.5);
        assertThat(criterion(poorScore, "alternatives-considered")).isZero();
        assertThat(criterion(poorScore, "data-handling")).isZero();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }

    @Test
    void changeSetPenalisesSkeletonsAndRewardsTestsAndApplicableDiffs() {
        Score skeleton = score(changeSet, good);
        assertThat(criterion(skeleton, "content-complete")).isLessThan(0.5);
        assertThat(criterion(skeleton, "tests-accompany")).as("most production files have a test").isBetween(0.8, 1.0);
        ChangeSet full = Payloads.changeSet("url-shortener/src/main/java/com/x/Feature.java", "class Feature {}");
        Score fullScore = score(full, ScoringContext.empty());
        assertThat(criterion(fullScore, "content-complete")).isEqualTo(1.0);
        assertThat(fullScore.value()).isGreaterThan(skeleton.value());

        String existing = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java";
        assertThat(index.containsPath(existing)).isTrue();
        ChangeSet repoChanges = new ChangeSet("HEAD", List.of(
                new ChangeSet.FileChange(existing, ChangeSet.Operation.MODIFY, "java", "s", ChangeSet.ContentKind.DIFF,
                        "--- a/" + existing + "\n+++ b/" + existing + "\n@@ -1,1 +1,1 @@\n-package a;\n+package b;\n",
                        List.of()),
                new ChangeSet.FileChange(existing.replace("LinkController", "Other"), ChangeSet.Operation.MODIFY,
                        "java", "s", ChangeSet.ContentKind.DIFF, "this is not a diff", List.of()),
                new ChangeSet.FileChange(existing.replace("LinkController", "LinkService").replace("/api/",
                        "/service/"), ChangeSet.Operation.CREATE, "java", "s", ChangeSet.ContentKind.NONE, "",
                        List.of())), "./mvnw verify", List.of(), "", List.of());
        Score repoScore = score(repoChanges, context(WorkspaceMode.REPO, index));
        assertThat(criterion(repoScore, "diffs-applicable")).isEqualTo(0.5);
        assertThat(criterion(repoScore, "paths-consistent-with-index")).as("modify of a missing file, create of an "
                + "existing one").isCloseTo(1.0 / 3, within(1e-12));
        assertThat(criterion(repoScore, "content-complete")).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(criterion(repoScore, "tests-accompany")).isZero();
        assertThat(criterion(repoScore, "rollback-plan")).isZero();
    }

    @Test
    void testPlanRewardsCoverageAndNegativeCases() {
        Score goodScore = score(testPlan, good);
        assertThat(criterion(goodScore, "fr-coverage")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "negative-cases")).isEqualTo(1.0);
        Score poorScore = score(Payloads.testPlan(), context(WorkspaceMode.NONE, null, spec));
        assertThat(criterion(poorScore, "fr-coverage")).isLessThan(1.0);
        assertThat(criterion(poorScore, "negative-cases")).isZero();
        assertThat(criterion(poorScore, "test-levels")).isEqualTo(0.5);
        assertThat(criterion(score(Payloads.testPlan(), ScoringContext.empty()), "negative-cases")).isZero();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }

    @Test
    void verificationRewardsPassingBlockingChecksTraceabilityAndBuilds() {
        Score goodScore = score(verification, good);
        assertThat(criterion(goodScore, "blocking-checks-passed")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "build")).as("no build ran").isEqualTo(0.5);
        VerificationReport failedBuild = new VerificationReport(List.of(Check.fail("a", "x"), Check.pass("b", "y"),
                Check.advisory("c", false, "z")), new VerificationReport.Traceability(4, 1, List.of("FR-2", "FR-3",
                "FR-4")), new VerificationReport.BuildOutcome(true, "./mvnw verify", 1, 10L, "failed", List.of()),
                false, List.of("fix"));
        Score poorScore = score(failedBuild, ScoringContext.empty());
        assertThat(criterion(poorScore, "blocking-checks-passed")).isEqualTo(0.5);
        assertThat(criterion(poorScore, "verification-passed")).isZero();
        assertThat(criterion(poorScore, "traceability")).isEqualTo(0.25);
        assertThat(criterion(poorScore, "advisory-findings")).isEqualTo(0.5);
        assertThat(criterion(poorScore, "build")).isZero();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }

    @Test
    void documentationRewardsCompleteSectionsAndApiCoverage() {
        Score goodScore = score(docs, good);
        assertThat(criterion(goodScore, "api-reference-covers-design")).isEqualTo(1.0);
        DocumentationBundle poor = new DocumentationBundle("readme", "", " ", "changelog", List.of());
        Score poorScore = score(poor, good);
        assertThat(criterion(poorScore, "sections-present")).isEqualTo(0.4);
        assertThat(criterion(poorScore, "api-reference-covers-design")).isZero();
        assertThat(criterion(poorScore, "runbook-rollback")).isZero();
        assertThat(criterion(poorScore, "adrs-match-decisions")).isZero();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }

    @Test
    void securityReviewRewardsStrideCoverageAndNoUnmitigatedHighFindings() {
        Score goodScore = score(security, good);
        assertThat(criterion(goodScore, "no-unmitigated-high")).isEqualTo(1.0);
        assertThat(criterion(goodScore, "stride-coverage")).isEqualTo(1.0);
        SecurityReview poor = new SecurityReview(List.of(new SecurityReview.Threat("T-1",
                SecurityReview.StrideCategory.SPOOFING, "d", "m", SecurityReview.ThreatStatus.OPEN)),
                List.of(Check.fail("input-validation-in-design", "x"), Check.fail("dependency-inventory", "HIGH")),
                List.of(new SecurityReview.Finding(Severity.HIGH, "t", "r")), List.of(), false);
        Score poorScore = score(poor, ScoringContext.empty());
        assertThat(criterion(poorScore, "no-unmitigated-high")).isZero();
        assertThat(criterion(poorScore, "stride-coverage")).isCloseTo(1.0 / 6, within(1e-12));
        assertThat(criterion(poorScore, "threats-treated")).isZero();
        assertThat(criterion(poorScore, "checks-passed")).isZero();
        assertThat(poorScore.criterion("dependency-inventory").orElseThrow().applies()).isTrue();
        assertThat(criterion(poorScore, "dependency-inventory")).isZero();
        assertThat(goodScore.criterion("dependency-inventory").orElseThrow().applies()).isFalse();
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }

    @Test
    void releaseDecisionMustBeConsistentWithItsInputs() {
        Score goodScore = score(release, good);
        assertThat(criterion(goodScore, "decision-consistent")).isEqualTo(1.0);
        assertThat(ArtifactScorers.ReleaseReadinessScorer.expectedDecision(release, good))
                .isEqualTo(ReleaseReadiness.GoNoGo.GO_WITH_CONDITIONS);

        ReleaseReadiness optimistic = Payloads.release(ReleaseReadiness.GoNoGo.GO);
        ScoringContext failedVerification = context(WorkspaceMode.NONE, null, Payloads.verification(false));
        Score poorScore = score(optimistic, failedVerification);
        assertThat(criterion(poorScore, "decision-consistent")).as("GO although verification failed").isZero();
        assertThat(ArtifactScorers.ReleaseReadinessScorer.expectedDecision(optimistic, failedVerification))
                .isEqualTo(ReleaseReadiness.GoNoGo.NO_GO);
        assertThat(criterion(score(optimistic, ScoringContext.empty()), "decision-consistent")).isEqualTo(1.0);
        assertThat(goodScore.value()).isGreaterThan(poorScore.value());
    }
}
