package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.MutableClock;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * The engine-wide circuit breaker per LLM provider: consecutive provider failures across stages and runs open it,
 * an open circuit routes attempts to the deterministic agents, and after the cooldown one trial decides whether it
 * closes or re-opens.
 */
class ProviderCircuitBreakerTest {

    private static final String PROVIDER = "fake-llm";
    private static final String AGENT_ID = PROVIDER + ":model";
    private static final String TEST_DESIGN = WorkflowDefinitions.TEST_DESIGN;
    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";

    /** An LLM-like test designer whose health the test switches. */
    static final class FlakyLlmAgent implements StageAgent {
        final AtomicInteger calls = new AtomicInteger();
        volatile FailureKind failure = FailureKind.TRANSIENT;
        volatile String message = "provider " + PROVIDER + " error: overloaded";

        @Override
        public AgentRole role() {
            return AgentRole.TEST_DESIGN;
        }

        @Override
        public String id() {
            return AGENT_ID;
        }

        @Override
        public StageResult execute(StageContext context) throws Exception {
            calls.incrementAndGet();
            FailureKind kind = failure;
            if (kind != null) {
                throw new StageFailure(kind, message);
            }
            return StageResult.success(Payloads.testPlan(), "designed by the provider");
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private final FlakyLlmAgent flaky = new FlakyLlmAgent();
    private final EngineHarness harness = new EngineHarness().provider(PROVIDER, flaky);

    @AfterEach
    void close() {
        harness.close();
    }

    private String run(String scenario) {
        return run(scenario, RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO).withProvider(PROVIDER));
    }

    private String run(String scenario, RunOptions options) {
        String id = harness.engine().start(REQUIREMENT, scenario, options).id();
        harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);
        return id;
    }

    private List<AuditEvent> events(String id, AuditEventType type) {
        return harness.events(id).stream().filter(e -> e.type() == type && TEST_DESIGN.equals(e.stageId())).toList();
    }

    private ProviderCircuit circuit() {
        return harness.engine().providerCircuits().get(PROVIDER);
    }

    @Test
    void consecutiveFailuresAcrossRunsOpenTheCircuitAndTheCooldownLetsOneTrialCloseOrReopenIt() {
        harness.config(b -> b.clock(clock).providerCircuitBreaker(
                new EngineConfig.ProviderCircuitBreaker(4, Duration.ofMinutes(10)))).start();
        assertThat(harness.engine().providerCircuits()).containsOnlyKeys(PROVIDER);
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.CLOSED);

        // run 1: three provider failures exhaust the stage; the circuit stays closed below the threshold of 4
        String first = run("circuit-1");
        assertThat(flaky.calls.get()).isEqualTo(3);
        assertThat(events(first, AuditEventType.FALLBACK_ENGAGED)).singleElement()
                .satisfies(e -> assertThat(e.data()).doesNotContainKey("reason").containsEntry("kind", "TRANSIENT"));
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.CLOSED);
        assertThat(circuit().consecutiveFailures()).isEqualTo(3);

        // run 2: the fourth consecutive failure opens the circuit; the scheduled retry goes to the fallback
        String second = run("circuit-2");
        assertThat(flaky.calls.get()).isEqualTo(4);
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.OPEN);
        assertThat(circuit().consecutiveFailures()).isEqualTo(4);
        assertThat(circuit().openedAt()).isEqualTo(clock.instant());
        assertThat(circuit().retryAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(circuit().lastFailure()).contains("TRANSIENT").contains("overloaded");
        assertThat(events(second, AuditEventType.RETRY_SCHEDULED)).hasSize(1);
        assertThat(events(second, AuditEventType.FALLBACK_ENGAGED)).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("reason", "circuit open").containsEntry("providerId", PROVIDER)
                    .containsEntry("from", AGENT_ID).containsEntry("to", AgentRegistry.FALLBACK_AGENT_ID)
                    .containsEntry("consecutiveFailures", 4);
            assertThat(e.message()).contains("circuit of provider fake-llm is open");
        });
        assertThat(harness.engine().get(second).stage(TEST_DESIGN).activeAgent())
                .isEqualTo(AgentRegistry.FALLBACK_AGENT_ID);

        // run 3: while open, the provider is not called at all
        String third = run("circuit-3");
        assertThat(flaky.calls.get()).isEqualTo(4);
        assertThat(events(third, AuditEventType.FALLBACK_ENGAGED)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("reason", "circuit open"));
        assertThat(events(third, AuditEventType.STAGE_STARTED)).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("agent", AgentRegistry.FALLBACK_AGENT_ID);
            assertThat(String.valueOf(e.data().get("agentSelection"))).contains("circuit of provider fake-llm is open");
        });
        assertThat(harness.engine().get(third).counters().fallbacks()).isEqualTo(1);

        // after the cooldown one trial runs; it fails, so the circuit re-opens for another cooldown
        clock.advance(Duration.ofMinutes(11));
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.HALF_OPEN);
        String fourth = run("circuit-4");
        assertThat(flaky.calls.get()).isEqualTo(5);
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.OPEN);
        assertThat(circuit().openedAt()).isEqualTo(clock.instant());
        assertThat(events(fourth, AuditEventType.STAGE_STARTED).getFirst().data().get("agentSelection"))
                .isEqualTo("provider agent (half-open circuit trial)");
        assertThat(events(fourth, AuditEventType.FALLBACK_ENGAGED)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("reason", "circuit open"));

        // after the next cooldown the provider is healthy again: the trial succeeds and closes the circuit
        clock.advance(Duration.ofMinutes(11));
        flaky.failure = null;
        String fifth = run("circuit-5");
        assertThat(flaky.calls.get()).isEqualTo(6);
        assertThat(events(fifth, AuditEventType.FALLBACK_ENGAGED)).isEmpty();
        assertThat(harness.engine().get(fifth).stage(TEST_DESIGN).activeAgent()).isEqualTo(AGENT_ID);
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.CLOSED);
        assertThat(circuit().consecutiveFailures()).isZero();
        assertThat(circuit().openedAt()).isNull();
        assertThat(circuit().lastFailure()).isNull();

        // closed again: the next run calls the provider directly
        run("circuit-6");
        assertThat(flaky.calls.get()).isEqualTo(7);
        Map<String, ProviderCircuit> view = harness.engine().providerCircuits();
        assertThatThrownBy(() -> view.put("x", circuit())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failureTextsAreRedactedAndBoundedBeforeTheyReachTheRunTheAuditTrailAndTheCircuit() {
        harness.config(b -> b.clock(clock)).start();
        String key = "sk-ant-api03-" + "Q".repeat(40);
        flaky.message = "provider " + PROVIDER + " error: 401 invalid x-api-key " + key + "; request body: "
                + "{\"prompt\": \"" + "p".repeat(20_000) + "\"}";

        String id = run("redacted");

        List<AuditEvent> failures = harness.events(id).stream().filter(e -> TEST_DESIGN.equals(e.stageId())
                && (e.type() == AuditEventType.RETRY_SCHEDULED || e.type() == AuditEventType.FALLBACK_ENGAGED))
                .toList();
        assertThat(failures).hasSize(3);
        assertThat(failures).allSatisfy(e -> {
            assertThat(e.message()).doesNotContain(key).contains("[redacted]");
            assertThat(String.valueOf(e.data().get("error"))).doesNotContain(key).startsWith("provider fake-llm error")
                    .hasSizeLessThanOrEqualTo(OrchestrationEngine.MAX_FAILURE_TEXT);
        });
        assertThat(circuit().lastFailure()).doesNotContain(key).contains("[redacted]");
        assertThat(harness.events(id)).noneMatch(e -> e.message().contains(key)
                || String.valueOf(e.data()).contains(key));
    }

    @Test
    void injectedFaultsAndFatalErrorsDoNotCountAndASuccessResetsTheCount() {
        harness.config(b -> b.clock(clock)).start();
        flaky.failure = FailureKind.FATAL;
        run("fatal");
        assertThat(flaky.calls.get()).isEqualTo(1);
        assertThat(circuit().consecutiveFailures()).as("FATAL is not provider-attributable").isZero();

        flaky.failure = null;
        run("faults", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO).withProvider(PROVIDER)
                .withFaults(List.of(new FaultSpec(TEST_DESIGN, 2, FaultKind.TRANSIENT))));
        assertThat(flaky.calls.get()).as("two injected faults, then the provider").isEqualTo(2);
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.CLOSED);
        assertThat(circuit().consecutiveFailures()).isZero();

        flaky.failure = FailureKind.INVALID_OUTPUT;
        run("invalid");
        assertThat(circuit().state()).as("three INVALID_OUTPUT failures reach the default threshold")
                .isEqualTo(ProviderCircuit.State.OPEN);
        assertThat(circuit().consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void aDisabledBreakerNeverOpens() {
        harness.config(b -> b.clock(clock).providerCircuitBreaker(EngineConfig.ProviderCircuitBreaker.DISABLED))
                .start();
        String first = run("disabled-1");
        String second = run("disabled-2");
        assertThat(flaky.calls.get()).isEqualTo(6);
        assertThat(circuit().state()).isEqualTo(ProviderCircuit.State.CLOSED);
        assertThat(circuit().consecutiveFailures()).isEqualTo(6);
        for (String id : List.of(first, second)) {
            assertThat(events(id, AuditEventType.FALLBACK_ENGAGED)).singleElement()
                    .satisfies(e -> assertThat(e.data()).doesNotContainKey("reason"));
        }
    }
}
