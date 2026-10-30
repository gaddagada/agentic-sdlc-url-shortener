package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.ChainVerification;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.InvalidRunStateException;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.RunLeasedElsewhereException;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;

/**
 * Two engines ("nodes" {@code node-a} and {@code node-b}) share one H2 database through the JPA run store, the JPA
 * audit sink and the {@link JdbcRunLeaseProvider} (the core's {@code OrchestrationEngineLeaseTest}, through the
 * database): a run is executed by the node holding its lease, the other node's commands are refused with the holder
 * named while it runs, either node continues a run that waits for a human, and a run of a node that closed or died
 * is recovered by the other node once its lease is released or expired. The audit chain stays intact throughout.
 */
@DataJpaTest
@Import({JpaRunStore.class, JpaAuditSink.class, PersistenceFixtures.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcLeaseMultiNodeTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";
    private static final String DESIGN = WorkflowDefinitions.DESIGN;
    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Actor ALICE = Actor.human("alice");

    @Autowired
    private JpaRunStore store;

    @Autowired
    private JpaAuditSink audit;

    @Autowired
    private RunRepository runs;

    @Autowired
    private ArtifactRepository artifacts;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private WorkflowGraph graph;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final CountDownLatch slowImplementation = new CountDownLatch(1);
    private JdbcRunLeaseProvider leases;
    private OrchestrationEngine a;
    private OrchestrationEngine b;

    @BeforeEach
    void nodes() {
        leases = new JdbcRunLeaseProvider(dataSource, transactionManager, clock);
    }

    @AfterEach
    void cleanUp() {
        slowImplementation.countDown();
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
        events.deleteAll();
        artifacts.deleteAll();
        runs.deleteAll();
        JdbcClient.create(dataSource).sql("DELETE FROM run_leases").update();
    }

    @Test
    void aRunWaitingForAHumanIsContinuedByEitherNode() {
        a = node("node-a", null);
        b = node("node-b", null);
        String id = a.start(REQUIREMENT, "two-nodes", RunOptions.defaults()).id();
        awaitRun(a, id, r -> r.stage(DESIGN).status() == StageStatus.AWAITING_APPROVAL);
        assertThat(leases.holderOf(id)).as("the lease is released while the run waits").isEmpty();

        b.approve(id, DESIGN, ALICE, "approved on node b");
        awaitRun(b, id, r -> r.stage(RELEASE).status() == StageStatus.AWAITING_APPROVAL);
        assertThat(leases.holderOf(id)).isEmpty();
        assertThat(a.get(id).stage(RELEASE).status()).as("node a reads node b's progress from the database")
                .isEqualTo(StageStatus.AWAITING_APPROVAL);

        a.approve(id, RELEASE, ALICE, "shipped from node a");
        Run done = awaitRun(a, id, r -> r.status() == RunStatus.COMPLETED);
        assertThat(b.get(id).status()).isEqualTo(RunStatus.COMPLETED);

        List<AuditEvent> trail = audit.forRun(id, 0);
        assertValidChain(trail, done);
        assertThat(trail.getFirst().data()).containsEntry("nodeId", "node-a");
        assertThat(nodesOf(trail, AuditEventType.STAGE_STARTED, DESIGN)).containsOnly("node-a");
        assertThat(nodesOf(trail, AuditEventType.STAGE_STARTED, IMPLEMENT)).containsOnly("node-b");
        assertThat(runs.findById(id).orElseThrow().getNodeId()).as("the node that last started work")
                .isEqualTo("node-b");
        assertThat(leases.current(id)).as("no lease row is left behind").isEmpty();
    }

    @Test
    void commandsOfAnotherNodeAreRefusedWhileTheRunExecutesAndAllowedOnceTheLeaseExpired() {
        a = node("node-a", slowImplementation);
        b = node("node-b", null);
        String id = a.start(REQUIREMENT, "leased", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        awaitRun(a, id, r -> r.stage(IMPLEMENT).status() == StageStatus.RUNNING);

        assertThat(leases.holderOf(id)).contains("node-a");
        assertThatThrownBy(() -> b.stop(id, ALICE, "from b"))
                .isInstanceOfSatisfying(RunLeasedElsewhereException.class, e -> {
                    assertThat(e.runId()).isEqualTo(id);
                    assertThat(e.holder()).isEqualTo("node-a");
                })
                .isInstanceOf(InvalidRunStateException.class);
        assertThat(b.get(id).stage(IMPLEMENT).status()).as("reads need no lease").isEqualTo(StageStatus.RUNNING);
        b.recoverInterrupted();
        assertThat(b.get(id).status()).as("a run leased by a live node is not interrupted")
                .isEqualTo(RunStatus.RUNNING);

        clock.advance(TTL.plusSeconds(1));
        assertThat(leases.holderOf(id)).as("node a stalled and its lease expired").isEmpty();
        Run stopped = b.stop(id, ALICE, "node a is unresponsive");
        assertThat(stopped.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.OPERATOR_STOP);
        slowImplementation.countDown();

        // node a's late result is discarded (it may briefly hold the lease to find out: retry, as a client would)
        await().atMost(TIMEOUT).ignoreException(RunLeasedElsewhereException.class)
                .until(() -> b.resume(id, ALICE).status() != RunStatus.SAFE_STOPPED);
        awaitRun(b, id, r -> r.stage(RELEASE).status() == StageStatus.AWAITING_APPROVAL);
        await().atMost(TIMEOUT).ignoreException(RunLeasedElsewhereException.class)
                .until(() -> b.approve(id, RELEASE, ALICE, null) != null);
        Run done = awaitRun(b, id, r -> r.status() == RunStatus.COMPLETED);

        List<AuditEvent> trail = audit.forRun(id, 0);
        assertValidChain(trail, done);
        assertThat(nodesOf(trail, AuditEventType.STAGE_STARTED, IMPLEMENT)).containsExactly("node-a", "node-b");
    }

    @Test
    void aClosedNodeReleasesItsLeasesAndTheOtherNodeRecoversItsRunAtOnce() {
        a = node("node-a", slowImplementation);
        b = node("node-b", null);
        String id = a.start(REQUIREMENT, "handover", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        awaitRun(a, id, r -> r.stage(IMPLEMENT).status() == StageStatus.RUNNING);

        a.close();
        assertThat(leases.current(id)).as("close released the lease row").isEmpty();
        b.recoverInterrupted();

        Run recovered = b.get(id);
        assertThat(recovered.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(recovered.haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);
        assertThat(audit.forRun(id, 0)).filteredOn(e -> e.type() == AuditEventType.RUN_INTERRUPTED).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("nodeId", "node-b"));
        slowImplementation.countDown();
        b.resume(id, ALICE);
        Run waiting = awaitRun(b, id, r -> r.stage(RELEASE).status() == StageStatus.AWAITING_APPROVAL);
        assertValidChain(audit.forRun(id, 0), waiting);
    }

    @Test
    void aRunOfACrashedNodeIsRecoveredOnlyOnceItsLeaseExpired() {
        a = node("node-a", slowImplementation);
        b = node("node-b", null);
        String id = a.start(REQUIREMENT, "crash", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        awaitRun(a, id, r -> r.stage(IMPLEMENT).status() == StageStatus.RUNNING);
        // node a "crashes": it never renews, releases or completes anything again (its attempt stays blocked)

        b.recoverInterrupted();
        assertThat(b.get(id).status()).as("the lease is still valid").isEqualTo(RunStatus.RUNNING);
        clock.advance(TTL);
        b.recoverInterrupted();

        Run recovered = b.get(id);
        assertThat(recovered.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(recovered.haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);
        assertThat(recovered.stage(IMPLEMENT).status()).isIn(StageStatus.PENDING, StageStatus.STALE);
        assertThat(leases.holderOf(id)).as("recovery released the lease it took").isEmpty();
    }

    private OrchestrationEngine node(String nodeId, @Nullable CountDownLatch implementationGate) {
        List<StageAgent> agents = new ArrayList<>();
        for (StageAgent agent : DeterministicAgents.all(null)) {
            agents.add(implementationGate != null && agent.role() == AgentRole.IMPLEMENTATION
                    ? new GatedAgent(agent, implementationGate) : agent);
        }
        return new OrchestrationEngine(graph, AgentRegistry.of(agents), PolicyEngine.withDefaultRules(), store, audit,
                NoopMetricsRecorder.INSTANCE, EngineConfig.builder().backoffScale(0).clock(clock).nodeId(nodeId)
                .leaseProvider(leases).leaseTtl(TTL).build());
    }

    private static Run awaitRun(OrchestrationEngine engine, String id, Predicate<Run> condition) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20)).until(() -> condition.test(engine.get(id)));
        return engine.get(id);
    }

    private static void assertValidChain(List<AuditEvent> trail, Run run) {
        ChainVerification chain = AuditChain.verify(trail);
        assertThat(chain.valid()).as(chain.summary()).isTrue();
        assertThat(chain.length()).isEqualTo(run.auditSeq());
    }

    private static List<Object> nodesOf(List<AuditEvent> trail, AuditEventType type, String stageId) {
        return trail.stream().filter(e -> e.type() == type && stageId.equals(e.stageId()))
                .map(e -> e.data().get("nodeId")).toList();
    }

    /** The deterministic agent of a role that waits for a gate before executing (a slow attempt). */
    private record GatedAgent(StageAgent delegate, CountDownLatch gate) implements StageAgent {

        @Override
        public AgentRole role() {
            return delegate.role();
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public StageResult execute(StageContext context) throws Exception {
            gate.await(20, TimeUnit.SECONDS);
            return delegate.execute(context);
        }
    }
}
