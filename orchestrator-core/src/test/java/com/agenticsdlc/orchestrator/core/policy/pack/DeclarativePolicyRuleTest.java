package com.agenticsdlc.orchestrator.core.policy.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Clarification;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.testsupport.Runs;

class DeclarativePolicyRuleTest {

    private static final PolicyPack PACK = new PolicyPack("acme", "2.1.0", List.of(), List.of());

    private static Run run(AutonomyLevel autonomy) {
        return Runs.create("Create short links.", RunOptions.defaults().withAutonomy(autonomy));
    }

    private static DeclarativePolicyRule rule(GatePhase phase, List<String> stages, Condition when, Effect effect) {
        return new DeclarativePolicyRule(PACK, new DeclarativeRule("ORG-001", "test rule", phase, stages, when, effect,
                null, "matched"));
    }

    private static boolean matches(Condition when, PolicyContext ctx) {
        return rule(ctx.phase(), List.of(), when, Effect.DENY).evaluate(ctx).isPresent();
    }

    private static PolicyContext exit(Run run, String stageId, AgentRole role, ArtifactPayload payload) {
        return PolicyContext.exit(run, stageId, role, payload);
    }

    private static ChangeSet changes(ChangeSet.Operation operation, String... paths) {
        return new ChangeSet("HEAD", java.util.Arrays.stream(paths).map(p -> new ChangeSet.FileChange(p, operation,
                "sql", "s", ChangeSet.ContentKind.FULL, "line1\nline2", List.of())).toList(), "./mvnw verify",
                List.of(), "revert", List.of());
    }

    @Test
    void mapsEffectsOntoVerdictsAndPrefixesThePack() {
        PolicyContext ctx = PolicyContext.runStart(run(AutonomyLevel.GUIDED), "store the SSN");
        Condition when = new Condition.IntentMatches(List.of("ssn"));
        assertThat(rule(GatePhase.RUN_START, List.of(), when, Effect.DENY).evaluate(ctx)).get().isEqualTo(
                new PolicyFinding("ORG-001", Severity.HIGH, Verdict.DENY, "[acme@2.1.0] matched"));
        assertThat(rule(GatePhase.RUN_START, List.of(), when, Effect.REQUIRE_APPROVAL).evaluate(ctx)).get()
                .satisfies(f -> {
                    assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
                    assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
                });
        assertThat(rule(GatePhase.RUN_START, List.of(), when, Effect.WARN).evaluate(ctx)).get().satisfies(f -> {
            assertThat(f.verdict()).as("a warning never blocks").isEqualTo(Verdict.ALLOW);
            assertThat(f.severity()).isEqualTo(Severity.LOW);
        });
        DeclarativePolicyRule adapter = rule(GatePhase.RUN_START, List.of(), when, Effect.WARN);
        assertThat(adapter.id()).isEqualTo("ORG-001");
        assertThat(adapter.name()).isEqualTo("test rule");
        assertThat(adapter.rule().message()).isEqualTo("matched");
    }

    @Test
    void appliesOnlyToItsPhaseAndStages() {
        Condition always = new Condition.AutonomyIs(AutonomyLevel.GUIDED);
        Run run = run(AutonomyLevel.GUIDED);
        DeclarativePolicyRule implementOnly = rule(GatePhase.ENTRY, List.of("implement"), always, Effect.DENY);
        assertThat(implementOnly.evaluate(PolicyContext.entry(run, "implement", AgentRole.IMPLEMENTATION))).isPresent();
        assertThat(implementOnly.evaluate(PolicyContext.entry(run, "design", AgentRole.ARCHITECTURE))).isEmpty();
        assertThat(implementOnly.evaluate(exit(run, "implement", AgentRole.IMPLEMENTATION, Payloads.taskPlan())))
                .as("other phase").isEmpty();
        assertThat(implementOnly.evaluate(PolicyContext.runStart(run, "x"))).isEmpty();
        assertThat(rule(GatePhase.ENTRY, List.of(), always, Effect.DENY)
                .evaluate(PolicyContext.entry(run, "design", AgentRole.ARCHITECTURE))).as("all stages").isPresent();
    }

    @Test
    void intentMatchesTheRequirementClarificationsAndNormalizedStatementCaseInsensitively() {
        Condition pii = new Condition.IntentMatches(List.of("\\bpii\\b", "social security"));
        Run run = run(AutonomyLevel.GUIDED);
        assertThat(matches(pii, PolicyContext.runStart(run, "Export PII for marketing"))).isTrue();
        assertThat(matches(pii, PolicyContext.runStart(run, "Export pipelines"))).isFalse();

        Run clarified = run(AutonomyLevel.GUIDED);
        clarified.setClarification(new Clarification("intake", List.of(), Map.of("q1", "Include the Social Security "
                + "number"), Instant.EPOCH, Instant.EPOCH, "alice"));
        assertThat(matches(pii, PolicyContext.entry(clarified, "design", AgentRole.ARCHITECTURE))).isTrue();

        var spec = Payloads.requirementSpec(RiskLevel.LOW);
        var piiSpec = new com.agenticsdlc.orchestrator.core.artifact.RequirementSpec("Store PII safely.", spec.intent(),
                spec.classification(), spec.scopeIn(), spec.scopeOut(), spec.functional(), spec.nonFunctional(),
                spec.acceptanceCriteria(), spec.assumptions(), spec.openQuestions(), spec.ambiguity(),
                spec.preliminaryRisk());
        assertThat(matches(pii, exit(run, "intake", AgentRole.REQUIREMENTS, piiSpec))).isTrue();
        assertThat(matches(pii, exit(run, "intake", AgentRole.REQUIREMENTS, spec))).isFalse();
    }

    @Test
    void pathMatchesCandidateChangesImpactAndAcceptedArtifacts() {
        Condition migrations = new Condition.PathMatches(List.of("**/db/migration/V1__*"));
        Run run = run(AutonomyLevel.GUIDED);
        assertThat(matches(migrations, exit(run, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.MODIFY, "url-shortener/src/main/resources/db/migration/V1__init.sql"))))
                .isTrue();
        assertThat(matches(migrations, exit(run, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.CREATE, "./URL-SHORTENER/src/main/resources/DB/migration/v1__init.sql"))))
                .as("normalized and case-insensitive").isTrue();
        assertThat(matches(migrations, exit(run, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.CREATE, "url-shortener/src/main/resources/db/migration/V2__x.sql"))))
                .isFalse();

        ImpactAnalysis impact = new ImpactAnalysis("ws", 3, true, List.of(
                new ImpactAnalysis.ImpactedComponent("V1", ImpactAnalysis.ComponentType.RESOURCE,
                        "db/migration/V1__init.sql", 0.5, List.of(), ImpactAnalysis.ChangeNature.REGRESSION_RISK),
                new ImpactAnalysis.ImpactedComponent("LinkService", ImpactAnalysis.ComponentType.SERVICE,
                        "src/security/LinkService.java", 0.9, List.of(), ImpactAnalysis.ChangeNature.MODIFY)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "summary");
        assertThat(matches(migrations, exit(run, "impact", AgentRole.CODEBASE_ANALYSIS, impact)))
                .as("regression risks are not changes").isFalse();
        Condition security = new Condition.PathMatches(List.of("**/security/**"));
        assertThat(matches(security, exit(run, "impact", AgentRole.CODEBASE_ANALYSIS, impact))).isTrue();

        Run withChanges = Runs.withAccepted(run(AutonomyLevel.GUIDED), impact,
                changes(ChangeSet.Operation.MODIFY, "url-shortener/src/main/resources/db/migration/V1__init.sql"));
        assertThat(matches(migrations, PolicyContext.entry(withChanges, "verify", AgentRole.VERIFICATION))).isTrue();
        assertThat(matches(security, PolicyContext.entry(withChanges, "verify", AgentRole.VERIFICATION))).isTrue();
        assertThat(matches(migrations, PolicyContext.entry(run, "verify", AgentRole.VERIFICATION))).isFalse();
    }

    @Test
    void payloadMatchesTheCandidateCanonicalJsonOnly() {
        Condition modifyV1 = new Condition.PayloadMatches(List.of(
                "\"operation\":\"(MODIFY|DELETE)\",\"path\":\"[^\"]*db/migration/V1__"));
        Run run = run(AutonomyLevel.GUIDED);
        assertThat(matches(modifyV1, exit(run, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.MODIFY, "x/db/migration/V1__init.sql")))).isTrue();
        assertThat(matches(modifyV1, exit(run, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.CREATE, "x/db/migration/V1__init.sql")))).isFalse();
        assertThat(matches(new Condition.PayloadMatches(List.of("(?i)LINE1")), exit(run, "implement",
                AgentRole.IMPLEMENTATION, changes(ChangeSet.Operation.CREATE, "a.sql")))).isTrue();
        assertThat(matches(new Condition.PayloadMatches(List.of("LINE1")), exit(run, "implement",
                AgentRole.IMPLEMENTATION, changes(ChangeSet.Operation.CREATE, "a.sql")))).as("case-sensitive")
                .isFalse();
        assertThat(matches(modifyV1, PolicyContext.entry(run, "implement", AgentRole.IMPLEMENTATION))).isFalse();
    }

    @Test
    void riskAtLeastUsesTheProspectiveRunRisk() {
        Condition high = new Condition.RiskAtLeast(RiskLevel.HIGH);
        Run run = run(AutonomyLevel.GUIDED);
        assertThat(matches(high, PolicyContext.entry(run, "design", AgentRole.ARCHITECTURE))).isFalse();
        Run risky = Runs.withAccepted(run(AutonomyLevel.GUIDED), Payloads.requirementSpec(RiskLevel.LOW),
                Payloads.design(RiskLevel.HIGH));
        assertThat(matches(high, exit(risky, "design", AgentRole.ARCHITECTURE, Payloads.design(RiskLevel.HIGH))))
                .isTrue();
        assertThat(matches(new Condition.RiskAtLeast(RiskLevel.CRITICAL), exit(risky, "design",
                AgentRole.ARCHITECTURE, Payloads.design(RiskLevel.HIGH)))).isFalse();
    }

    @Test
    void changeSizeOverCountsFilesAndLines() {
        Run run = run(AutonomyLevel.GUIDED);
        PolicyContext twoFiles = exit(run, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.CREATE, "a.sql", "b.sql"));
        assertThat(matches(new Condition.ChangeSizeOver(1, 100), twoFiles)).isTrue();
        assertThat(matches(new Condition.ChangeSizeOver(2, 100), twoFiles)).isFalse();
        assertThat(matches(new Condition.ChangeSizeOver(5, 3), twoFiles)).as("4 content lines").isTrue();
        assertThat(matches(new Condition.ChangeSizeOver(0, 0), PolicyContext.entry(run, "verify",
                AgentRole.VERIFICATION))).as("no change set").isFalse();
        Run accepted = Runs.withAccepted(run(AutonomyLevel.GUIDED), changes(ChangeSet.Operation.CREATE, "a.sql"));
        assertThat(matches(new Condition.ChangeSizeOver(0, 100), PolicyContext.entry(accepted, "verify",
                AgentRole.VERIFICATION))).isTrue();
    }

    @Test
    void releaseDecisionAndAutonomyConditions() {
        Run run = run(AutonomyLevel.FULL_AUTO);
        PolicyContext noGo = exit(run, "release-readiness", AgentRole.RELEASE,
                Payloads.release(ReleaseReadiness.GoNoGo.NO_GO));
        assertThat(matches(new Condition.ReleaseDecisionIs(ReleaseReadiness.GoNoGo.NO_GO), noGo)).isTrue();
        assertThat(matches(new Condition.ReleaseDecisionIs(ReleaseReadiness.GoNoGo.GO), noGo)).isFalse();
        assertThat(matches(new Condition.ReleaseDecisionIs(ReleaseReadiness.GoNoGo.GO),
                PolicyContext.entry(run, "release-readiness", AgentRole.RELEASE))).isFalse();
        assertThat(matches(new Condition.AutonomyIs(AutonomyLevel.FULL_AUTO), noGo)).isTrue();
        assertThat(matches(new Condition.AutonomyIs(AutonomyLevel.STRICT), noGo)).isFalse();
    }

    @Test
    void combinatorsComposeConditions() {
        PolicyContext ctx = PolicyContext.runStart(run(AutonomyLevel.FULL_AUTO), "Export PII");
        Condition pii = new Condition.IntentMatches(List.of("pii"));
        Condition fullAuto = new Condition.AutonomyIs(AutonomyLevel.FULL_AUTO);
        Condition strict = new Condition.AutonomyIs(AutonomyLevel.STRICT);
        assertThat(matches(new Condition.AllOf(List.of(pii, fullAuto)), ctx)).isTrue();
        assertThat(matches(new Condition.AllOf(List.of(pii, strict)), ctx)).isFalse();
        assertThat(matches(new Condition.AnyOf(List.of(strict, pii)), ctx)).isTrue();
        assertThat(matches(new Condition.AnyOf(List.of(strict)), ctx)).isFalse();
        assertThat(matches(new Condition.Not(strict), ctx)).isTrue();
        assertThat(matches(new Condition.Not(new Condition.AnyOf(List.of(pii))), ctx)).isFalse();
    }

    @Test
    void samplePackRulesBehaveAsDocumented() {
        PolicyPack sample = PolicyPackLoader.sample();
        List<DeclarativePolicyRule> rules = sample.rules().stream().map(r -> new DeclarativePolicyRule(sample, r))
                .toList();
        Run guided = run(AutonomyLevel.GUIDED);

        Optional<PolicyFinding> v1 = rules.get(0).evaluate(exit(guided, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.MODIFY, "url-shortener/src/main/resources/db/migration/V1__init.sql")));
        assertThat(v1).get().satisfies(f -> assertThat(f.verdict()).isEqualTo(Verdict.DENY));
        assertThat(rules.get(0).evaluate(exit(guided, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.CREATE, "url-shortener/src/main/resources/db/migration/V1__init.sql"))))
                .as("creating V1 in a greenfield run is fine").isEmpty();

        assertThat(rules.get(1).evaluate(exit(guided, "implement", AgentRole.IMPLEMENTATION,
                changes(ChangeSet.Operation.MODIFY, "url-shortener/src/main/java/com/x/config/SecurityConfig.java"))))
                .get().satisfies(f -> assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL));

        assertThat(rules.get(2).evaluate(PolicyContext.runStart(guided, "Mask the SSN of every customer")))
                .get().satisfies(f -> assertThat(f.verdict()).isEqualTo(Verdict.ALLOW));
        assertThat(rules.get(2).evaluate(PolicyContext.runStart(guided, "Add a QR code endpoint"))).isEmpty();

        Run critical = Runs.withAccepted(run(AutonomyLevel.FULL_AUTO), Payloads.requirementSpec(RiskLevel.CRITICAL));
        assertThat(rules.get(3).evaluate(exit(critical, "decompose", AgentRole.PLANNING, Payloads.taskPlan())))
                .get().satisfies(f -> assertThat(f.message()).startsWith("[sample-baseline@1.0.0] CRITICAL risk"));
        assertThat(rules.get(3).evaluate(exit(Runs.withAccepted(run(AutonomyLevel.GUIDED),
                Payloads.requirementSpec(RiskLevel.CRITICAL)), "decompose", AgentRole.PLANNING, Payloads.taskPlan())))
                .isEmpty();
    }
}
