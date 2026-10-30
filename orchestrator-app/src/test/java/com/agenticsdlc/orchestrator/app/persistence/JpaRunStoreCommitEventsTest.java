package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;

/**
 * {@link JpaRunStore} publishes every committed change (list row, snapshot and audit events) to its commit listener
 * only after the transaction committed: never for a failed commit, and only at the end of an outer transaction that
 * commits. The engine's commits reach the run event bus in commit order with contiguous audit sequences.
 */
@DataJpaTest
@Import({JpaAuditSink.class, PersistenceFixtures.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaRunStoreCommitEventsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String REQUIREMENT = "Build a URL shortener service with click analytics and expiry.";

    @Autowired
    private RunRepository runs;

    @Autowired
    private ArtifactRepository artifacts;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private JpaAuditSink auditSink;

    @Autowired
    private JsonCodec codec;

    @Autowired
    private WorkflowGraph graph;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<RunCommitted> published = new CopyOnWriteArrayList<>();
    private OrchestrationEngine engine;

    @AfterEach
    void cleanUp() {
        if (engine != null) {
            engine.close();
        }
        events.deleteAll();
        artifacts.deleteAll();
        runs.deleteAll();
    }

    @Test
    void aCommitIsPublishedAfterTheTransactionWithItsListRowSnapshotAndEvents() {
        JpaRunStore store = store();
        Run run = Run.create("c1", graph, "greenfield", REQUIREMENT, RunOptions.defaults(), T0);
        List<AuditEvent> batch = List.of(event("c1", 1, AuditEventType.RUN_CREATED), event("c1", 2,
                AuditEventType.STAGE_READY));

        store.commit(run, batch, auditSink);

        assertThat(published).singleElement().satisfies(committed -> {
            assertThat(committed.runId()).isEqualTo("c1");
            assertThat(committed.events()).isEqualTo(batch);
            assertThat(committed.snapshot().status()).isEqualTo(RunStatus.RUNNING);
            assertThat(committed.item().startedBy()).as("actor of RUN_CREATED").isEqualTo("alice");
            assertThat(committed.item().scenario()).isEqualTo("greenfield");
            assertThat(committed.item().stagesTotal()).isEqualTo(graph.stages().size());
            assertThat(committed.item().requirement()).isEqualTo(REQUIREMENT);
        });
        assertThat(auditSink.forRun("c1", 0)).hasSize(2);
    }

    @Test
    void aFailedCommitPublishesNothing() {
        JpaRunStore store = store();
        Run run = Run.create("c2", graph, "custom", REQUIREMENT, RunOptions.defaults(), T0);
        AuditSink failing = new AuditSink() {
            @Override
            public void append(List<AuditEvent> batch) {
                throw new IllegalStateException("audit store unavailable");
            }

            @Override
            public List<AuditEvent> forRun(String runId, long afterSeq) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> store.commit(run, List.of(event("c2", 1, AuditEventType.RUN_CREATED)), failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(published).isEmpty();
        assertThat(runs.findById("c2")).as("rolled back").isEmpty();
    }

    @Test
    void insideAnOuterTransactionTheChangeIsPublishedOnlyWhenTheOuterTransactionCommits() {
        JpaRunStore store = store();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            store.save(Run.create("c3", graph, "custom", REQUIREMENT, RunOptions.defaults(), T0));
            assertThat(published).as("not before the outer commit").isEmpty();
        });
        assertThat(published).extracting(RunCommitted::runId).containsExactly("c3");

        outer.executeWithoutResult(status -> {
            store.save(Run.create("c4", graph, "custom", REQUIREMENT, RunOptions.defaults(), T0));
            status.setRollbackOnly();
        });
        assertThat(published).as("a rolled back save is never published").extracting(RunCommitted::runId)
                .containsExactly("c3");
    }

    @Test
    void theEngineFeedsTheBusInCommitOrderWithContiguousAuditSequences() {
        List<RunCommitted> received = new CopyOnWriteArrayList<>();
        RunEventBus bus = new RunEventBus(Runnable::run);
        bus.subscribe(received::add);
        JpaRunStore store = new JpaRunStore(runs, artifacts, codec, graph, transactionManager, bus);
        engine = new OrchestrationEngine(graph, AgentRegistry.of(DeterministicAgents.all(null)),
                PolicyEngine.withDefaultRules(), store, auditSink, NoopMetricsRecorder.INSTANCE,
                EngineConfig.builder().backoffScale(0).build());

        String id = engine.start(REQUIREMENT, "greenfield", RunOptions.defaults()).id();
        await().atMost(Duration.ofSeconds(30)).until(() -> engine.get(id).status() == RunStatus.WAITING_HUMAN);
        await().atMost(Duration.ofSeconds(5)).until(() -> !received.isEmpty()
                && received.getLast().snapshot().status() == RunStatus.WAITING_HUMAN);

        List<Long> seqs = received.stream().flatMap(c -> c.events().stream()).map(AuditEvent::seq).toList();
        assertThat(seqs).isNotEmpty();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).as("event %d", i).isEqualTo(i + 1L);
        }
        assertThat(seqs.getLast()).isEqualTo(engine.get(id).auditSeq());
        assertThat(received.getLast().item().pendingActions()).isEqualTo(1);
        assertThat(received.getLast().item().status()).isEqualTo("WAITING_HUMAN");
    }

    private JpaRunStore store() {
        return new JpaRunStore(runs, artifacts, codec, graph, transactionManager, published::add);
    }

    private static AuditEvent event(String runId, long seq, AuditEventType type) {
        return new AuditEvent(seq, T0.plusSeconds(seq), runId, null, type, Actor.human("alice"), "event " + seq,
                Map.of());
    }
}
