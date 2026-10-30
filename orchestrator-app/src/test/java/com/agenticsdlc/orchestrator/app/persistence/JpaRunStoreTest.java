package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.app.api.RunLauncher;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.audit.ChainVerification;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;

/**
 * {@link JpaRunStore} on H2 with the Flyway schema. The engine writes through the store from its own virtual
 * threads, so the tests run without a test-managed transaction ({@code NOT_SUPPORTED}): every adapter call commits
 * on its own, exactly as in production, and rows are deleted after each test.
 */
@DataJpaTest
@Import({JpaRunStore.class, JpaAuditSink.class, PersistenceFixtures.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaRunStoreTest {

    private static final String GREENFIELD = "Build a URL shortener service: create short links for HTTP and HTTPS "
            + "URLs with optional expiry, redirect with HTTP 302, record click analytics and expose health checks. "
            + "The service must prevent redirects to private networks.";
    private static final Actor ALICE = Actor.human("alice");
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private JpaRunStore store;

    @Autowired
    private JpaAuditSink auditSink;

    @Autowired
    private RunRepository runs;

    @Autowired
    private ArtifactRepository artifacts;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private JsonCodec codec;

    @Autowired
    private WorkflowGraph graph;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void aCompletedRunRoundTripsWithStagesArtifactsAndApprovals() {
        engine = newEngine(store);
        String id = engine.start(GREENFIELD, "greenfield", RunOptions.defaults()).id();
        awaitApproval(id, "design");
        engine.approve(id, "design", ALICE, "design ok");
        awaitApproval(id, "release-readiness");
        engine.approve(id, "release-readiness", ALICE, "ship it");
        Run done = awaitRun(id, r -> r.status() == RunStatus.COMPLETED);

        Run loaded = freshStore().load(id).orElseThrow();

        assertThat(codec.toJson(loaded.toSnapshot(codec))).isEqualTo(codec.toJson(done.toSnapshot(codec)));
        assertThat(loaded.stages()).allSatisfy(s -> assertThat(s.status()).isEqualTo(StageStatus.SUCCEEDED));
        assertThat(loaded.acceptedArtifacts()).hasSize(10);
        assertThat(loaded.approvals()).extracting(a -> a.stageId() + ":" + a.status() + ":" + a.decidedBy())
                .containsExactly("design:APPROVED:alice", "release-readiness:APPROVED:alice");
        assertThat(loaded.approvals()).allSatisfy(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.APPROVED));

        RunEntity row = runs.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("COMPLETED");
        assertThat(row.getScenario()).isEqualTo("greenfield");
        assertThat(row.getEndedAt()).isNotNull();
        assertThat(row.getVersion()).isPositive();
        List<ArtifactEntity> rows = artifacts.findByRunIdOrderByStageIdAscVersionAsc(id);
        assertThat(rows).hasSize(done.artifacts().size());
        assertThat(rows).allSatisfy(a -> {
            assertThat(a.getHash()).hasSize(64);
            assertThat(codec.hash(codec.readTree(a.getPayloadJson()))).isEqualTo(a.getHash());
        });
        assertThat(auditSink.forRun(id, 0)).extracting(e -> e.type()).contains(AuditEventType.RUN_COMPLETED);

        List<AuditEvent> trail = auditSink.forRun(id, 0);
        ChainVerification chain = AuditChain.verify(trail);
        assertThat(chain.valid()).as(chain.summary()).isTrue();
        assertThat(chain.length()).isEqualTo(done.auditSeq()).isEqualTo(trail.size());
        assertThat(trail.getLast().hash()).isEqualTo(done.auditHeadHash());
        assertThat(row.getAuditHeadHash()).isEqualTo(done.auditHeadHash());
        assertThat(row.getAutonomy()).isEqualTo("GUIDED");
        assertThat(row.getWorkspaceMode()).isEqualTo("NONE");
        assertThat(row.getStartedBy()).as("actor of RUN_CREATED").isEqualTo(Actor.system().id());

        assertThat(row.getRiskLevel()).isEqualTo(done.currentRisk().level().name());
        assertThat(row.getNodeId()).isEqualTo(EngineConfig.defaultNodeId());
    }

    @Test
    void theListingColumnsComeFromTheRunAndItsCommittedEvents() {
        Run run = Run.create("listed", graph, "custom", GREENFIELD, new RunOptions(AutonomyLevel.FULL_AUTO, null,
                List.of(), null, WorkspaceMode.REPO, Map.of(JpaRunStore.CREATED_BY_TAG, "tagged-operator")), T0);
        AuditEvent created = AuditChain.link(new AuditEvent(1, T0, "listed", null, AuditEventType.RUN_CREATED,
                ALICE, "run created", Map.of("nodeId", "node-7")), null);

        store.commit(run, List.of(created), auditSink);

        RunEntity row = runs.findById("listed").orElseThrow();
        assertThat(row.getAutonomy()).isEqualTo("FULL_AUTO");
        assertThat(row.getWorkspaceMode()).isEqualTo("REPO");
        assertThat(row.getStartedBy()).isEqualTo("alice");
        assertThat(row.getNodeId()).isEqualTo("node-7");
        assertThat(row.getRiskLevel()).isEqualTo("LOW");
        assertThat(row.getAuditHeadHash()).as("taken from the snapshot, not from the events").isNull();

        run.touch(T0.plusSeconds(1));
        store.save(run);
        RunEntity again = runs.findById("listed").orElseThrow();
        assertThat(again.getStartedBy()).as("kept without a RUN_CREATED event").isEqualTo("alice");
        assertThat(again.getNodeId()).as("kept without an event naming a node").isEqualTo("node-7");

        store.save(Run.create("tagged", graph, "custom", GREENFIELD, run.options(), T0));
        assertThat(runs.findById("tagged").orElseThrow().getStartedBy()).as("falls back to the run tag")
                .isEqualTo("tagged-operator");
        assertThat(JpaRunStore.CREATED_BY_TAG).isEqualTo(RunLauncher.CREATED_BY_TAG);
    }

    @Test
    void savingAgainUpdatesTheSnapshotAndInsertsArtifactsOnlyOnce() {
        engine = newEngine(store);
        String id = engine.start(GREENFIELD, "greenfield", RunOptions.defaults()).id();
        Run waiting = awaitApproval(id, "design");
        long version = runs.findById(id).orElseThrow().getVersion();
        int artifactRows = artifacts.findIdsByRunId(id).size();

        store.save(waiting);
        assertThat(runs.findById(id).orElseThrow().getVersion()).as("unchanged snapshot, no update")
                .isEqualTo(version);

        waiting.touch(waiting.updatedAt().plusSeconds(1));
        store.save(waiting);
        store.save(waiting);

        assertThat(runs.findById(id).orElseThrow().getVersion()).isEqualTo(version + 1);
        assertThat(store.load(id).orElseThrow().updatedAt()).isEqualTo(waiting.updatedAt());
        assertThat(artifacts.findIdsByRunId(id)).hasSize(artifactRows).hasSize(waiting.artifacts().size());
    }

    @Test
    void aRestartedEngineSeesPersistedRunsAndSafeStopsInterruptedOnes() {
        Run interrupted = Run.create("run-interrupted", graph, "custom", GREENFIELD, RunOptions.defaults(),
                Instant.parse("2026-10-01T10:00:00Z"));
        interrupted.markStarted(Instant.parse("2026-10-01T10:00:01Z"));
        store.save(interrupted);
        engine = newEngine(store);
        String waitingId = engine.start(GREENFIELD, "greenfield", RunOptions.defaults()).id();
        awaitApproval(waitingId, "design");
        engine.close();

        engine = newEngine(freshStore());
        engine.recoverInterrupted();

        assertThat(engine.list()).extracting(Run::id).contains("run-interrupted", waitingId);
        Run recovered = engine.get("run-interrupted");
        assertThat(recovered.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(recovered.haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);
        assertThat(engine.get(waitingId).status()).isEqualTo(RunStatus.WAITING_HUMAN);
        assertThat(auditSink.forRun("run-interrupted", 0)).extracting(e -> e.type())
                .contains(AuditEventType.RUN_INTERRUPTED, AuditEventType.SAFE_STOP);

        engine.approve(waitingId, "design", ALICE, null);
        awaitApproval(waitingId, "release-readiness");
    }

    @Test
    void commitPersistsTheSnapshotAndTheAuditEventsTogether() {
        Run run = Run.create("atomic-ok", graph, "custom", GREENFIELD, RunOptions.defaults(), T0);

        store.commit(run, List.of(event("atomic-ok", 1), event("atomic-ok", 2)), auditSink);

        assertThat(store.load("atomic-ok")).isPresent();
        assertThat(auditSink.forRun("atomic-ok", 0)).extracting(AuditEvent::seq).containsExactly(1L, 2L);
    }

    @Test
    void aFailingAuditInsertRollsBackTheSnapshotSave() {
        Run run = Run.create("atomic", graph, "custom", GREENFIELD, RunOptions.defaults(), T0);
        store.commit(run, List.of(event("atomic", 1)), auditSink);
        long version = runs.findById("atomic").orElseThrow().getVersion();
        String snapshot = runs.findById("atomic").orElseThrow().getSnapshotJson();

        run.markStarted(T0.plusSeconds(5));
        run.touch(T0.plusSeconds(5));
        // seq 2 is new, seq 1 violates the unique (run_id, seq) constraint: the whole commit must roll back
        assertThatThrownBy(() -> store.commit(run, List.of(event("atomic", 2), event("atomic", 1)), auditSink))
                .isInstanceOf(DataIntegrityViolationException.class);

        RunEntity row = runs.findById("atomic").orElseThrow();
        assertThat(row.getVersion()).as("snapshot update rolled back").isEqualTo(version);
        assertThat(row.getSnapshotJson()).isEqualTo(snapshot);
        assertThat(store.load("atomic").orElseThrow().startedAt()).isNull();
        assertThat(auditSink.forRun("atomic", 0)).extracting(AuditEvent::seq).as("seq 2 rolled back too")
                .containsExactly(1L);
    }

    @Test
    void aThrowingAuditSinkLeavesNoRunBehind() {
        Run run = Run.create("atomic-new", graph, "custom", GREENFIELD, RunOptions.defaults(), T0);
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

        assertThatThrownBy(() -> store.commit(run, List.of(event("atomic-new", 1)), failing))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit store unavailable");

        assertThat(runs.findById("atomic-new")).isEmpty();
        assertThat(store.load("atomic-new")).isEmpty();
    }

    @Test
    void loadOfAnUnknownRunIsEmptyAndCorruptSnapshotsAreSkipped() {
        store.save(Run.create("good", graph, "custom", GREENFIELD, RunOptions.defaults(), Instant.now()));
        RunEntity corrupt = new RunEntity("corrupt", "custom", Instant.now());
        corrupt.update("RUNNING", null, GREENFIELD, 1, Instant.now(), null, "{not json");
        runs.save(corrupt);

        assertThat(store.load("missing")).isEmpty();
        assertThat(store.loadAll()).extracting(Run::id).containsExactly("good");
    }

    @Test
    void overLongRequirementsAreTruncatedInTheQueryColumnOnly() {
        String requirement = "Build a service. " + "x".repeat(5000);
        store.save(Run.create("long", graph, "custom", requirement, RunOptions.defaults(), Instant.now()));

        assertThat(runs.findById("long").orElseThrow().getRequirement()).hasSize(RunEntity.REQUIREMENT_MAX);
        assertThat(store.load("long").orElseThrow().requirement()).isEqualTo(requirement);
    }

    private static AuditEvent event(String runId, long seq) {
        return new AuditEvent(seq, T0.plusSeconds(seq), runId, null, AuditEventType.RUN_CREATED, Actor.system(),
                "event #" + seq, Map.of());
    }

    private JpaRunStore freshStore() {
        return new JpaRunStore(runs, artifacts, codec, graph, transactionManager);
    }

    private OrchestrationEngine newEngine(JpaRunStore runStore) {
        return new OrchestrationEngine(graph, AgentRegistry.of(DeterministicAgents.all(null)),
                PolicyEngine.withDefaultRules(), runStore, auditSink, NoopMetricsRecorder.INSTANCE,
                EngineConfig.builder().backoffScale(0).build());
    }

    private Run awaitApproval(String id, String stageId) {
        return awaitRun(id, r -> r.stage(stageId).status() == StageStatus.AWAITING_APPROVAL
                && r.status() == RunStatus.WAITING_HUMAN);
    }

    private Run awaitRun(String id, Predicate<Run> condition) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20))
                .until(() -> condition.test(engine.get(id)));
        return engine.get(id);
    }
}
