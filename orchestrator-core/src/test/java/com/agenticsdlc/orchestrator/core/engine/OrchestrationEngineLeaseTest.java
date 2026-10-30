package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.ChainVerification;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;
import com.agenticsdlc.orchestrator.core.port.RunStore;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.MutableClock;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * Two engines ("nodes") share one store, one audit sink and one lease provider: a run is executed by the node that
 * holds its lease, the other node's commands are refused while it runs, and either node continues it once it waits
 * for a human.
 */
class OrchestrationEngineLeaseTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";
    private static final String DESIGN = WorkflowDefinitions.DESIGN;
    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;
    private static final Duration TTL = Duration.ofSeconds(60);

    private final InMemoryRunStore store = new InMemoryRunStore();
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final InMemoryRunLeaseProvider leases = new InMemoryRunLeaseProvider(clock);
    private final EngineHarness nodeA = node("node-a", clock, leases, TTL);
    private final EngineHarness nodeB = node("node-b", clock, leases, TTL);

    private EngineHarness node(String nodeId, Clock nodeClock, InMemoryRunLeaseProvider provider, Duration ttl) {
        return new EngineHarness(store, audit).config(b -> b.nodeId(nodeId).clock(nodeClock).leaseProvider(provider)
                .leaseTtl(ttl));
    }

    @AfterEach
    void close() {
        nodeA.close();
        nodeB.close();
    }

    private static FakeAgent blockingImplementation(CountDownLatch release) {
        return new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            release.await(10, TimeUnit.SECONDS);
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "slow implementation");
        });
    }

    private List<AuditEvent> events(String runId) {
        return audit.forRun(runId, 0);
    }

    private static List<Object> nodesOf(List<AuditEvent> events, AuditEventType type, String stageId) {
        return events.stream().filter(e -> e.type() == type && stageId.equals(e.stageId()))
                .map(e -> e.data().get("nodeId")).toList();
    }

    @Test
    void aRunWaitingForAHumanCanBeContinuedByAnyNode() {
        OrchestrationEngine a = nodeA.start();
        OrchestrationEngine b = nodeB.start();
        String id = a.start(REQUIREMENT, "two-nodes", RunOptions.defaults()).id();
        nodeA.awaitApproval(id, DESIGN);
        assertThat(leases.holderOf(id)).as("the lease is released while the run waits").isEmpty();

        b.approve(id, DESIGN, ALICE, "approved on node b");
        nodeB.awaitApproval(id, RELEASE);
        assertThat(leases.holderOf(id)).isEmpty();

        Run seenByA = a.get(id);
        assertThat(seenByA.stage(RELEASE).status()).as("node a reads node b's progress")
                .isEqualTo(StageStatus.AWAITING_APPROVAL);
        a.approve(id, RELEASE, ALICE, "shipped from node a");
        Run done = nodeA.awaitStatus(id, RunStatus.COMPLETED);
        assertThat(b.get(id).status()).isEqualTo(RunStatus.COMPLETED);

        List<AuditEvent> events = events(id);
        ChainVerification chain = AuditChain.verify(events);
        assertThat(chain.valid()).as(chain.summary()).isTrue();
        assertThat(chain.length()).isEqualTo(done.auditSeq());
        assertThat(events.getFirst().data()).containsEntry("nodeId", "node-a");
        assertThat(nodesOf(events, AuditEventType.STAGE_STARTED, DESIGN)).containsOnly("node-a");
        assertThat(nodesOf(events, AuditEventType.STAGE_STARTED, IMPLEMENT)).containsOnly("node-b");
        assertThat(nodesOf(events, AuditEventType.STAGE_STARTED, RELEASE)).containsOnly("node-b");
        assertThat(done.acceptedPayload(ReleaseReadiness.class).orElseThrow().changeRecord().approvals())
                .extracting(ReleaseReadiness.ApprovalRecord::comment)
                .containsExactly("approved on node b", "shipped from node a");
    }

    @Test
    void commandsOfAnotherNodeAreRefusedWhileTheRunExecutesAndAllowedOnceTheLeaseExpired() {
        CountDownLatch slow = new CountDownLatch(1);
        nodeA.agent(blockingImplementation(slow));
        OrchestrationEngine a = nodeA.start();
        OrchestrationEngine b = nodeB.start();
        String id = a.start(REQUIREMENT, "leased", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        nodeA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
        try {
            assertThat(leases.holderOf(id)).contains("node-a");
            assertThatThrownBy(() -> b.stop(id, ALICE, "from b"))
                    .isInstanceOfSatisfying(RunLeasedElsewhereException.class, e -> {
                        assertThat(e.runId()).isEqualTo(id);
                        assertThat(e.holder()).isEqualTo("node-a");
                    })
                    .isInstanceOf(InvalidRunStateException.class).hasMessageContaining("node-a");
            assertThatThrownBy(() -> b.approve(id, RELEASE, ALICE, null))
                    .isInstanceOf(RunLeasedElsewhereException.class);
            assertThat(b.get(id).stage(IMPLEMENT).status()).as("reads need no lease").isEqualTo(StageStatus.RUNNING);
            b.recoverInterrupted();
            assertThat(b.get(id).status()).as("a run leased by a node is not interrupted")
                    .isEqualTo(RunStatus.RUNNING);

            clock.advance(TTL.plusSeconds(1));
            assertThat(leases.holderOf(id)).as("node a stalled and its lease expired").isEmpty();
            Run stopped = b.stop(id, ALICE, "node a is unresponsive");
            assertThat(stopped.status()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(stopped.haltReason()).isEqualTo(HaltReason.OPERATOR_STOP);
            assertThat(stopped.stage(IMPLEMENT).status()).isIn(StageStatus.PENDING, StageStatus.STALE);
        } finally {
            slow.countDown();
        }

        // node a's late result is discarded (it may briefly hold the lease to find that out: retry, as a client would)
        await().atMost(EngineHarness.TIMEOUT).ignoreException(RunLeasedElsewhereException.class)
                .until(() -> b.resume(id, ALICE).status() != RunStatus.SAFE_STOPPED);
        nodeB.awaitApproval(id, RELEASE);
        await().atMost(EngineHarness.TIMEOUT).ignoreException(RunLeasedElsewhereException.class)
                .until(() -> b.approve(id, RELEASE, ALICE, null) != null);
        Run done = nodeB.awaitStatus(id, RunStatus.COMPLETED);
        assertThat(done.stage(IMPLEMENT).status()).isEqualTo(StageStatus.SUCCEEDED);
        List<AuditEvent> events = events(id);
        ChainVerification chain = AuditChain.verify(events);
        assertThat(chain.valid()).as(chain.summary()).isTrue();
        assertThat(nodesOf(events, AuditEventType.STAGE_STARTED, IMPLEMENT)).containsExactly("node-a", "node-b");
        assertThat(events).filteredOn(e -> e.type() == AuditEventType.RUN_RESUMED).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("nodeId", "node-b"));
    }

    @Test
    void aLongAttemptKeepsItsLeaseThroughHeartbeats() throws InterruptedException {
        Duration shortTtl = Duration.ofMillis(600);
        InMemoryRunLeaseProvider shared = new InMemoryRunLeaseProvider();
        try (EngineHarness realA = node("node-a", Clock.systemUTC(), shared, shortTtl)) {
            CountDownLatch slow = new CountDownLatch(1);
            realA.agent(blockingImplementation(slow));
            OrchestrationEngine a = realA.start();
            try (EngineHarness realB = node("node-b", Clock.systemUTC(), shared, shortTtl)) {
                OrchestrationEngine b = realB.start();
                String id = a.start(REQUIREMENT, "heartbeat", RunOptions.defaults()
                        .withAutonomy(AutonomyLevel.FULL_AUTO)).id();
                realA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
                try {
                    Thread.sleep(shortTtl.multipliedBy(3).toMillis());
                    assertThat(shared.holderOf(id)).as("renewed by the attempt's heartbeat").contains("node-a");
                    assertThatThrownBy(() -> b.stop(id, ALICE, "from b"))
                            .isInstanceOf(RunLeasedElsewhereException.class);
                } finally {
                    slow.countDown();
                }
                realA.awaitApproval(id, RELEASE);
                assertThat(shared.holderOf(id)).isEmpty();
            }
        }
    }

    @Test
    void closingANodeReleasesItsLeasesSoAnotherNodeCanRecoverItsRuns() {
        CountDownLatch slow = new CountDownLatch(1);
        nodeA.agent(blockingImplementation(slow));
        OrchestrationEngine a = nodeA.start();
        OrchestrationEngine b = nodeB.start();
        String id = a.start(REQUIREMENT, "handover", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        nodeA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
        try {
            a.close();
            assertThat(leases.holderOf(id)).isEmpty();
            b.recoverInterrupted();
            Run recovered = b.get(id);
            assertThat(recovered.status()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(recovered.haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);
            assertThat(audit.ofType(id, AuditEventType.RUN_INTERRUPTED)).singleElement()
                    .satisfies(e -> assertThat(e.data()).containsEntry("nodeId", "node-b"));
        } finally {
            slow.countDown();
        }
        b.resume(id, ALICE);
        nodeB.awaitApproval(id, RELEASE);
        ChainVerification chain = AuditChain.verify(events(id));
        assertThat(chain.valid()).as(chain.summary()).isTrue();
    }

    @Test
    void aFailedStartReleasesTheLeaseOfTheDiscardedRun() {
        OrchestrationEngine a = nodeA.storeDecorator(s -> new RunStore() {
            @Override
            public void save(Run run) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public Optional<Run> load(String runId) {
                return s.load(runId);
            }

            @Override
            public List<Run> loadAll() {
                return s.loadAll();
            }
        }).start();
        assertThatThrownBy(() -> a.start(REQUIREMENT, "ghost", RunOptions.defaults()))
                .hasMessageContaining("database unavailable");
        assertThat(a.list()).isEmpty();
        assertThat(store.loadAll()).isEmpty();
        assertThat(leases.toString()).as("the lease of the discarded run was released").contains("leases=0");
    }
}
