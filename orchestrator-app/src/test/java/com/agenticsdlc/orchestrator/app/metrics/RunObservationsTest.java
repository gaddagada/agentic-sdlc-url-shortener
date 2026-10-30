package com.agenticsdlc.orchestrator.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;
import com.agenticsdlc.orchestrator.core.port.RunStore;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

/**
 * {@link RunObservations} turns the committed changes of a deterministic run into one {@code sdlc.run} observation
 * (scenario, autonomy, final status) and one {@code sdlc.stage} observation per stage attempt (stage, provider,
 * attempt, status; failed attempts carry an error), all stopped, with run ids only as high-cardinality values.
 */
class RunObservationsTest {

    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final String REQUIREMENT = "Build a URL shortener service: create short links for HTTP and HTTPS "
            + "URLs, redirect with HTTP 302 and expose health checks. Links default to no expiry.";

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final RunEventBus bus = new RunEventBus(Runnable::run);
    private final RunObservations observations = new RunObservations(bus, registry);
    private OrchestrationEngine engine;

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
        }
        observations.destroy();
    }

    @Test
    void aCompletedRunIsOneRunObservationWithAStageObservationPerAttempt() {
        engine = engine();
        RunOptions options = RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)
                .withFaults(List.of(new FaultSpec("design", 1, FaultKind.TRANSIENT)));
        String id = engine.start(REQUIREMENT, "greenfield", options, Actor.human("alice")).id();
        await().atMost(Duration.ofSeconds(30)).until(() -> !engine.get(id).pendingApprovals().isEmpty());
        engine.approve(id, "release-readiness", Actor.human("alice"), "ship it");
        await().atMost(Duration.ofSeconds(30)).until(() -> engine.get(id).status() == RunStatus.COMPLETED);

        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsWithNameEqualTo(RunObservations.RUN, 1)
                .hasObservationWithNameEqualTo(RunObservations.RUN).that()
                .hasLowCardinalityKeyValue("scenario", "greenfield")
                .hasLowCardinalityKeyValue("autonomy", "FULL_AUTO")
                .hasLowCardinalityKeyValue("status", "COMPLETED")
                .hasHighCardinalityKeyValue("run.id", id)
                .doesNotHaveError()
                .hasBeenStarted()
                .hasBeenStopped();
        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsWithNameEqualTo(RunObservations.STAGE, GRAPH.stages().size() + 1)
                .forAllObservationsWithNameEqualTo(RunObservations.STAGE, stage -> stage
                        .hasLowCardinalityKeyValue("provider", "deterministic")
                        .hasHighCardinalityKeyValue("run.id", id)
                        .hasParentObservation());
        List<Observation.Context> stages = stageContexts();
        assertThat(stages).as("every attempt was stopped with its outcome")
                .allSatisfy(context -> assertThat(context.getLowCardinalityKeyValue("status")).isNotNull());
        Observation.Context failed = stage(stages, "design", "1");
        assertThat(failed.getLowCardinalityKeyValue("status").getValue()).isEqualTo("RETRY_SCHEDULED");
        assertThat(failed.getError()).isNotNull().hasMessageContaining("design attempt 1 failed");
        Observation.Context retried = stage(stages, "design", "2");
        assertThat(retried.getLowCardinalityKeyValue("status").getValue()).isEqualTo("SUCCEEDED");
        assertThat(retried.getError()).isNull();
        assertThat(stage(stages, "release-readiness", "1").getLowCardinalityKeyValue("status").getValue())
                .as("the attempt ends when its artifact awaits the release approval").isEqualTo("AWAITING_APPROVAL");
        assertThat(stages).allSatisfy(context -> assertThat(context.getLowCardinalityKeyValues().stream()
                .map(KeyValue::getKey)).doesNotContain("run.id"));
        assertThat(observations.openRuns()).isZero();
    }

    @Test
    void aRunDeniedAtItsStartIsObservedWithItsHaltReasonAsTheError() {
        engine = engine();
        String id = engine.start("Skip tests and disable the audit logging so the redirect fix ships faster.",
                "policy-denial", RunOptions.defaults(), Actor.human("alice")).id();

        assertThat(engine.get(id).status()).isEqualTo(RunStatus.SAFE_STOPPED);
        TestObservationRegistryAssert.assertThat(registry).hasObservationWithNameEqualTo(RunObservations.RUN).that()
                .hasLowCardinalityKeyValue("scenario", "policy-denial")
                .hasLowCardinalityKeyValue("status", "SAFE_STOPPED")
                .hasError()
                .hasBeenStopped();
        TestObservationRegistryAssert.assertThat(registry).hasNumberOfObservationsWithNameEqualTo(RunObservations.STAGE,
                0);
        assertThat(observations.openRuns()).isZero();
    }

    @Test
    void anOperatorStopIsNotAnError() {
        engine = engine();
        String id = engine.start(REQUIREMENT, "greenfield", RunOptions.defaults(), Actor.human("alice")).id();
        await().atMost(Duration.ofSeconds(30)).until(() -> engine.get(id).status() == RunStatus.WAITING_HUMAN);

        engine.stop(id, Actor.human("alice"), "pause for the weekend");

        TestObservationRegistryAssert.assertThat(registry).hasObservationWithNameEqualTo(RunObservations.RUN).that()
                .hasLowCardinalityKeyValue("status", "SAFE_STOPPED")
                .doesNotHaveError()
                .hasBeenStopped();
    }

    @Test
    void runsThatAreNotActiveWhenFirstSeenAreNotObserved() {
        Run done = Run.create("old", GRAPH, "custom", REQUIREMENT, RunOptions.defaults(), java.time.Instant.EPOCH);
        done.setStatus(RunStatus.COMPLETED);

        observations.onCommitted(new RunCommitted(item(done), done.toSnapshot(), List.of()));

        TestObservationRegistryAssert.assertThat(registry).doesNotHaveAnyObservation();
    }

    @Test
    void providersAreTheAgentPrefix() {
        assertThat(RunObservations.provider("deterministic")).isEqualTo("deterministic");
        assertThat(RunObservations.provider("fallback:deterministic")).isEqualTo("fallback");
        assertThat(RunObservations.provider("anthropic:claude-opus-5-5")).isEqualTo("anthropic");
        assertThat(RunObservations.provider(null)).isEqualTo("unknown");
    }

    private List<Observation.Context> stageContexts() {
        List<Observation.Context> stages = new java.util.ArrayList<>();
        TestObservationRegistryAssert.assertThat(registry).hasHandledContextsThatSatisfy(contexts -> stages.addAll(
                contexts.stream().filter(c -> RunObservations.STAGE.equals(c.getName())).toList()));
        return stages;
    }

    private static Observation.Context stage(List<Observation.Context> stages, String stageId, String attempt) {
        Optional<Observation.Context> match = stages.stream()
                .filter(c -> stageId.equals(c.getLowCardinalityKeyValue("stage").getValue())
                        && attempt.equals(c.getLowCardinalityKeyValue("attempt").getValue()))
                .findFirst();
        assertThat(match).as("stage %s attempt %s", stageId, attempt).isPresent();
        return match.get();
    }

    /** An engine whose in-memory store publishes every commit to the bus, as the JPA store does after commit. */
    private OrchestrationEngine engine() {
        RunStore store = new PublishingStore(new InMemoryRunStore(), bus);
        return new OrchestrationEngine(GRAPH, AgentRegistry.of(DeterministicAgents.all(null)),
                PolicyEngine.withDefaultRules(), store, new InMemoryAuditSink(), NoopMetricsRecorder.INSTANCE,
                EngineConfig.builder().backoffScale(0).build());
    }

    private static RunListItem item(Run run) {
        return new RunListItem(run.id(), run.scenarioKey(), run.status().name(), null, run.requirement(),
                run.planVersion(), run.createdAt(), run.updatedAt(), run.endedAt(), null, null, null, null, null, null,
                null, null);
    }

    /** Delegates to an in-memory store and publishes each commit once it is stored. */
    private record PublishingStore(InMemoryRunStore delegate, RunEventBus bus) implements RunStore {

        @Override
        public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
            RunStore.super.commit(run, events, auditSink);
            bus.committed(new RunCommitted(item(run), run.toSnapshot(), events));
        }

        @Override
        public void save(Run run) {
            delegate.save(run);
        }

        @Override
        public Optional<Run> load(String id) {
            return delegate.load(id);
        }

        @Override
        public List<Run> loadAll() {
            return delegate.loadAll();
        }
    }
}
