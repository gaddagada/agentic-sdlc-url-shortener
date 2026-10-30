package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackException;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackLoader;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;

/**
 * Declarative policy packs inside the engine: a pack rule can halt a run, and the audit trail names the active pack.
 */
class PolicyPackEngineTest {

    private static final String PACK = """
            {"name": "acme", "version": "3.0.0", "disabledRules": ["CHG-001"], "rules": [
              {"id": "ACME-001", "phase": "RUN_START",
               "when": {"type": "intentMatches", "regexes": ["\\\\bcrypto ?mining\\\\b"]},
               "effect": "DENY", "message": "crypto mining is not an approved workload"},
              {"id": "ACME-002", "phase": "EXIT", "stages": ["implement"],
               "when": {"type": "pathMatches", "globs": ["**/com/example/**"]},
               "effect": "DENY", "message": "the example package is frozen"},
              {"id": "ACME-003", "phase": "RUN_START",
               "when": {"type": "intentMatches", "regexes": ["analytics"]},
               "effect": "WARN", "message": "analytics changes need a dashboard update"}]}""";

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> packData(AuditEvent event) {
        assertThat(event.data()).containsKey("policyPack");
        return (Map<String, Object>) event.data().get("policyPack");
    }

    @Test
    void aDeclarativeDenyAtRunStartSafeStopsTheRunAndTheAuditRecordsThePack() {
        PolicyPack pack = PolicyPackLoader.fromJson(PACK);
        PolicyEngine policy = PolicyEngine.withDefaultRulesAnd(pack);
        OrchestrationEngine engine = harness.policy(policy).start();
        assertThat(engine.policyPack()).isEqualTo(pack);

        Run run = engine.start("Add crypto mining to the shortener servers.", "deny", RunOptions.defaults());

        assertThat(run.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(run.haltDetail()).contains("ACME-001").contains("[acme@3.0.0] crypto mining");
        assertThat(run.policyFindings()).anySatisfy(f -> assertThat(f.ruleId()).isEqualTo("ACME-001"));
        List<AuditEvent> events = harness.events(run.id());
        AuditEvent created = events.stream().filter(e -> e.type() == AuditEventType.RUN_CREATED).findFirst()
                .orElseThrow();
        assertThat(packData(created)).containsEntry("name", "acme").containsEntry("version", "3.0.0")
                .containsEntry("hash", pack.hash());
        AuditEvent denied = events.stream().filter(e -> e.type() == AuditEventType.POLICY_DENIED).findFirst()
                .orElseThrow();
        assertThat(packData(denied)).containsEntry("hash", policy.packHash());
        assertThat(denied.data().get("rules")).isEqualTo(List.of("ACME-001"));
    }

    @Test
    void aDeclarativeExitDenyFailsTheStageAndWarningsNeverBlock() {
        OrchestrationEngine engine = harness.policy(PolicyEngine.withDefaultRulesAnd(PolicyPackLoader.fromJson(PACK)))
                .start();
        String id = engine.start("Record click analytics for short links.", "exit-deny",
                RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.stage("implement").status()).isNotEqualTo(StageStatus.SUCCEEDED);
        assertThat(stopped.policyFindings()).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("ACME-003");
            assertThat(f.verdict()).isEqualTo("ALLOW");
        });
        AuditEvent denied = harness.events(id).stream().filter(e -> e.type() == AuditEventType.POLICY_DENIED)
                .findFirst().orElseThrow();
        assertThat(denied.stageId()).isEqualTo("implement");
        assertThat(denied.data()).containsEntry("phase", "EXIT");
        assertThat(packData(denied)).containsEntry("name", "acme");
    }

    @Test
    void withoutAPackNothingIsRecorded() {
        OrchestrationEngine engine = harness.start();
        Run run = engine.start("Add crypto mining to the shortener servers.", "no-pack", RunOptions.defaults());
        assertThat(engine.policyPack()).isNull();
        assertThat(harness.events(run.id())).allSatisfy(e -> assertThat(e.data()).doesNotContainKey("policyPack"));
    }

    @Test
    void packsDisableBuiltInsAndAppendTheirRules() {
        PolicyPack pack = PolicyPackLoader.fromJson(PACK);
        PolicyEngine policy = PolicyEngine.withDefaultRulesAnd(pack);
        List<String> ids = policy.rules().stream().map(PolicyRule::id).toList();
        List<String> expected = new java.util.ArrayList<>(DefaultPolicyRules.ids());
        expected.remove("CHG-001");
        expected.addAll(List.of("ACME-001", "ACME-002", "ACME-003"));
        assertThat(ids).containsExactlyElementsOf(expected);
        assertThat(policy.pack()).isEqualTo(pack);
        assertThat(policy.packHash()).isEqualTo(pack.hash());
        assertThat(PolicyEngine.withDefaultRules().packHash()).isNull();
        assertThat(PolicyEngine.withDefaultRules().pack()).isNull();
    }

    @Test
    void protectedBuiltInsCannotBeDisabledEvenInCode() {
        PolicyPack unsafe = new PolicyPack("unsafe", "1", List.of(), List.of("CHG-002", "SEC-002", "GOV-003"));
        assertThatThrownBy(() -> PolicyEngine.withDefaultRulesAnd(unsafe))
                .isInstanceOfSatisfying(PolicyPackException.class, e -> assertThat(e.problems()).hasSize(3)
                        .allSatisfy(p -> assertThat(p).contains("cannot be disabled")));
    }
}
