package com.agenticsdlc.orchestrator.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class PolicyEngineTest {

    private static Run run(AutonomyLevel autonomy) {
        return Run.create("r1", new WorkflowGraph(WorkflowDefinitions.defaultSdlc()), "test", "Create short links.",
                RunOptions.defaults().withAutonomy(autonomy), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PolicyRule rule(String id, Verdict verdict) {
        return new PolicyRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
                return verdict == null ? Optional.empty()
                        : Optional.of(new PolicyFinding(id, Severity.LOW, verdict, id + " says " + verdict));
            }
        };
    }

    @Test
    void combinesFindingsWithDenyOverApprovalOverAllow() {
        PolicyContext ctx = PolicyContext.runStart(run(AutonomyLevel.GUIDED), "x");
        assertThat(new PolicyEngine(List.of(rule("A", Verdict.ALLOW), rule("B", Verdict.REQUIRE_APPROVAL)))
                .evaluate(ctx).verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
        PolicyDecision decision = new PolicyEngine(List.of(rule("A", Verdict.REQUIRE_APPROVAL), rule("B", Verdict.DENY),
                rule("C", Verdict.ALLOW), rule("D", null))).evaluate(ctx);
        assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
        assertThat(decision.findings()).extracting(PolicyFinding::ruleId).containsExactly("A", "B", "C");
        assertThat(decision.findingsWith(Verdict.DENY)).hasSize(1);
        assertThat(new PolicyEngine(List.of()).evaluate(ctx)).isEqualTo(PolicyDecision.allow());
    }

    @Test
    void failingRuleFailsClosed() {
        PolicyRule broken = new PolicyRule() {
            @Override
            public String id() {
                return "BROKEN";
            }

            @Override
            public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        PolicyDecision decision = new PolicyEngine(List.of(broken)).evaluate(
                PolicyContext.runStart(run(AutonomyLevel.GUIDED), "x"));
        assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
        assertThat(decision.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.message()).contains("failing closed");
        });
    }

    @Test
    void rejectsDuplicateRuleIds() {
        assertThatThrownBy(() -> new PolicyEngine(List.of(rule("A", null), rule("A", null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultRulesHaveTheSpecifiedIds() {
        assertThat(PolicyEngine.withDefaultRules().rules()).extracting(PolicyRule::id).containsExactly("SEC-001",
                "SEC-002", "SEC-003", "CMP-001", "CMP-002", "CHG-001", "CHG-002", "CHG-003", "GOV-001", "GOV-002",
                "GOV-003");
    }

    @Test
    void defaultRulesAllowACleanRequirementAndRequireReleaseApproval() {
        PolicyEngine engine = PolicyEngine.withDefaultRules();
        Run run = run(AutonomyLevel.FULL_AUTO);
        assertThat(engine.evaluate(PolicyContext.runStart(run, "Add a bulk link creation API.")).verdict())
                .isEqualTo(Verdict.ALLOW);
        assertThat(engine.evaluate(PolicyContext.entry(run, "design", AgentRole.ARCHITECTURE)).verdict())
                .isEqualTo(Verdict.ALLOW);
        PolicyDecision release = engine.evaluate(PolicyContext.exit(run, "release-readiness", AgentRole.RELEASE,
                Payloads.forRole(AgentRole.RELEASE)));
        assertThat(release.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
        assertThat(release.findings()).extracting(PolicyFinding::ruleId).containsExactly("CHG-002", "GOV-003");
        assertThat(engine.evaluate(PolicyContext.exit(run, "design", AgentRole.ARCHITECTURE,
                Payloads.design(RiskLevel.HIGH))).verdict()).as("FULL_AUTO has no risk-based design approval")
                .isEqualTo(Verdict.ALLOW);
    }
}
