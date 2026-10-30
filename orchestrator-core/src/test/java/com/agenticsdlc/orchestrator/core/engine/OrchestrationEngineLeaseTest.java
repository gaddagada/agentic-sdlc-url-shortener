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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
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
import com.agenticsdlc.orchestrator.core.port.RunLease;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;
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
    void aLongAttemptKeepsItsLeaseThroughHeartbeats() {
        // Lease expiry is judged with the shared mutable clock, so the test does not depend on how promptly the
        // heartbeat threads are scheduled: the clock jumps past the lease's expiry, and the next heartbeat of the
        // running attempt (every ttl / 3 of real time) must renew it.
        Duration shortTtl = Duration.ofMillis(300);
        try (EngineHarness realA = node("node-a", clock, leases, shortTtl)) {
            CountDownLatch slow = new CountDownLatch(1);
            realA.agent(blockingImplementation(slow));
            OrchestrationEngine a = realA.start();
            try (EngineHarness realB = node("node-b", clock, leases, shortTtl)) {
                OrchestrationEngine b = realB.start();
                String id = a.start(REQUIREMENT, "heartbeat", RunOptions.defaults()
                        .withAutonomy(AutonomyLevel.FULL_AUTO)).id();
                realA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
                try {
                    for (int lapse = 0; lapse < 3; lapse++) {
                        // every lease granted or renewed before the jump expires before the new "now"
                        clock.advance(shortTtl.multipliedBy(2));
                        Instant jumped = clock.instant();
                        await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(10)).until(() ->
                                leases.current(id).map(l -> l.expiresAt().isAfter(jumped)).orElse(false));
                        assertThat(leases.holderOf(id)).as("renewed by the attempt's heartbeat").contains("node-a");
                    }
                    assertThatThrownBy(() -> b.stop(id, ALICE, "from b"))
                            .isInstanceOf(RunLeasedElsewhereException.class);
                } finally {
                    slow.countDown();
                }
                realA.awaitApproval(id, RELEASE);
                assertThat(leases.holderOf(id)).isEmpty();
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
    void recoveryCountsTheRecoveredRunsAndLogsAtInfoOnlyWhenItRecoveredSome() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(OrchestrationEngine.class);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        CountDownLatch slow = new CountDownLatch(1);
        try {
            nodeA.agent(blockingImplementation(slow));
            OrchestrationEngine a = nodeA.start();
            OrchestrationEngine b = nodeB.start();
            String id = a.start(REQUIREMENT, "count", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
            nodeA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);

            appender.list.clear();
            assertThat(b.recoverInterruptedCount()).as("node a executes the run").isZero();
            assertThat(recoveryLines(appender, ch.qos.logback.classic.Level.INFO)).isEmpty();
            assertThat(recoveryLines(appender, ch.qos.logback.classic.Level.DEBUG))
                    .containsExactly("recovery finished interruptedRuns=0 node=node-b");
            assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().startsWith("run not recovered"))
                    .singleElement().satisfies(e -> assertThat(e.getLevel())
                            .isEqualTo(ch.qos.logback.classic.Level.DEBUG));

            a.close();
            appender.list.clear();
            assertThat(b.recoverInterruptedCount()).isEqualTo(1);
            assertThat(recoveryLines(appender, ch.qos.logback.classic.Level.INFO))
                    .containsExactly("recovery finished interruptedRuns=1 node=node-b");
            assertThat(b.get(id).haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);

            appender.list.clear();
            b.recoverInterrupted();
            assertThat(b.recoverInterruptedCount()).as("nothing left to recover").isZero();
            assertThat(recoveryLines(appender, ch.qos.logback.classic.Level.INFO)).isEmpty();
            assertThat(audit.ofType(id, AuditEventType.RUN_INTERRUPTED)).hasSize(1);
        } finally {
            slow.countDown();
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    private static List<String> recoveryLines(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
            ch.qos.logback.classic.Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("recovery finished")).toList();
    }

    /** A lease provider whose releases can fail, be dropped (a crashed node) and whose reclaim can be unsupported. */
    static final class FlakyLeases implements RunLeaseProvider {
        final InMemoryRunLeaseProvider delegate;
        final AtomicInteger failReleases = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        volatile boolean dropReleases;
        volatile boolean reclaimSupported = true;
        /** Renewals are refused (the lease is lost) without anyone else taking it. */
        volatile boolean failRenewals;
        /** The next renewal finds the lease taken over by this node id (and is refused). */
        volatile String stealOnRenewFor;

        FlakyLeases(InMemoryRunLeaseProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl) {
            return delegate.tryAcquire(runId, ownerId, ttl);
        }

        @Override
        public boolean renew(RunLease lease, Duration ttl) {
            String thief = stealOnRenewFor;
            if (thief != null) {
                stealOnRenewFor = null;
                delegate.release(lease);
                delegate.tryAcquire(lease.runId(), thief, ttl);
                return false;
            }
            return !failRenewals && delegate.renew(lease, ttl);
        }

        @Override
        public void release(RunLease lease) {
            releaseCalls.incrementAndGet();
            if (failReleases.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("lease table unavailable");
            }
            if (!dropReleases) {
                delegate.release(lease);
            }
        }

        @Override
        public Optional<RunLease> reclaim(String runId, String ownerId, Duration ttl) {
            return reclaimSupported ? delegate.reclaim(runId, ownerId, ttl) : Optional.empty();
        }

        @Override
        public Optional<String> holderOf(String runId) {
            return delegate.holderOf(runId);
        }
    }

    @Test
    void aFailedReleaseKeepsTheLeasePendingSoTheNodeIsNotLockedOutAndTheRunIsNotEvicted() {
        FlakyLeases flaky = new FlakyLeases(leases);
        flaky.failReleases.set(1);
        try (EngineHarness flakyA = new EngineHarness(store, audit).config(b -> b.nodeId("node-a").clock(clock)
                .leaseProvider(flaky).leaseTtl(TTL).registryRetention(Duration.ZERO))) {
            OrchestrationEngine a = flakyA.start();
            OrchestrationEngine b = nodeB.start();
            String id = a.start(REQUIREMENT, "pending", RunOptions.defaults()).id();
            flakyA.awaitApproval(id, DESIGN);

            assertThat(flaky.releaseCalls.get()).as("the release at the design gate failed").isEqualTo(1);
            assertThat(leases.holderOf(id)).contains("node-a");
            assertThat(a.evictIdleRuns()).as("a run with a pending release is never evicted").isZero();
            assertThat(a.isCached(id)).isTrue();
            assertThatThrownBy(() -> b.stop(id, ALICE, "from b")).isInstanceOfSatisfying(
                    RunLeasedElsewhereException.class, e -> assertThat(e.holder()).isEqualTo("node-a"));

            a.approve(id, DESIGN, ALICE, "approved on the node that kept the lease");
            flakyA.awaitApproval(id, RELEASE);
            assertThat(leases.holderOf(id)).as("released normally at the next gate").isEmpty();
            a.evictIdleRuns();
            assertThat(a.isCached(id)).as("evictable again once nothing is pending").isFalse();
        }
    }

    @Test
    void maintenanceRetriesPendingReleasesAndCloseRetriesAFailedReleaseOnce() {
        FlakyLeases flaky = new FlakyLeases(leases);
        flaky.failReleases.set(2);
        try (EngineHarness flakyA = new EngineHarness(store, audit).config(b -> b.nodeId("node-a").clock(clock)
                .leaseProvider(flaky).leaseTtl(TTL))) {
            OrchestrationEngine a = flakyA.start();
            String id = a.start(REQUIREMENT, "maintenance", RunOptions.defaults()).id();
            flakyA.awaitApproval(id, DESIGN);
            assertThat(leases.holderOf(id)).contains("node-a");

            assertThat(a.releasePendingLeases()).as("the provider still fails").isZero();
            assertThat(leases.holderOf(id)).contains("node-a");
            assertThat(a.recoverInterruptedCount()).as("nothing to recover, but the pending release is retried")
                    .isZero();
            assertThat(leases.holderOf(id)).isEmpty();
            assertThat(a.releasePendingLeases()).isZero();
            assertThat(flaky.releaseCalls.get()).isEqualTo(3);
        }

        CountDownLatch slow = new CountDownLatch(1);
        FlakyLeases closing = new FlakyLeases(leases);
        try (EngineHarness closingA = new EngineHarness(store, audit).config(b -> b.nodeId("node-c").clock(clock)
                .leaseProvider(closing).leaseTtl(TTL))) {
            closingA.agent(blockingImplementation(slow));
            OrchestrationEngine a = closingA.start();
            String id = a.start(REQUIREMENT, "close", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO))
                    .id();
            closingA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
            closing.failReleases.set(1);
            try {
                a.close();
            } finally {
                slow.countDown();
            }
            assertThat(closing.releaseCalls.get()).as("one failed release and one retry").isEqualTo(2);
            assertThat(leases.holderOf(id)).as("released by the retry").isEmpty();
        }
    }

    @Test
    void aNodeRestartedWithItsNodeIdReclaimsItsOwnLeaseAndIsNeverRefusedWithItsOwnId() {
        OrchestrationEngine first = nodeA.start();
        String waiting = first.start(REQUIREMENT, "reclaim", RunOptions.defaults()).id();
        nodeA.awaitApproval(waiting, DESIGN);
        first.close();
        // the previous incarnation of node-a still holds a lease (for example it died between acquiring and releasing)
        assertThat(leases.tryAcquire(waiting, "node-a", TTL)).isPresent();

        FlakyLeases noReclaim = new FlakyLeases(leases);
        noReclaim.reclaimSupported = false;
        try (EngineHarness restarted = new EngineHarness(store, audit).config(b -> b.nodeId("node-a").clock(clock)
                .leaseProvider(noReclaim).leaseTtl(TTL))) {
            OrchestrationEngine a = restarted.start();
            assertThatThrownBy(() -> a.approve(waiting, DESIGN, ALICE, null))
                    .isInstanceOf(InvalidRunStateException.class).isNotInstanceOf(RunLeasedElsewhereException.class)
                    .hasMessageContaining("still leased by this node (node-a)");
        }
        OrchestrationEngine b = nodeB.start();
        assertThatThrownBy(() -> b.approve(waiting, DESIGN, ALICE, null))
                .isInstanceOfSatisfying(RunLeasedElsewhereException.class,
                        e -> assertThat(e.holder()).isEqualTo("node-a"));

        try (EngineHarness restarted = new EngineHarness(store, audit).config(b2 -> b2.nodeId("node-a").clock(clock)
                .leaseProvider(leases).leaseTtl(TTL))) {
            OrchestrationEngine a = restarted.start();
            a.approve(waiting, DESIGN, ALICE, "approved after the restart");
            restarted.awaitApproval(waiting, RELEASE);
            assertThat(leases.holderOf(waiting)).isEmpty();
        }
    }

    @Test
    void aRunningRunOfACrashedNodeIsRecoveredByTheSameNodeIdThroughAReclaim() {
        CountDownLatch slow = new CountDownLatch(1);
        FlakyLeases crashing = new FlakyLeases(leases);
        String id;
        try (EngineHarness crashed = new EngineHarness(store, audit).config(b -> b.nodeId("node-a").clock(clock)
                .leaseProvider(crashing).leaseTtl(TTL))) {
            crashed.agent(blockingImplementation(slow));
            OrchestrationEngine a = crashed.start();
            id = a.start(REQUIREMENT, "crash", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
            crashed.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
            crashing.dropReleases = true; // a crash: the lease is never released
            try {
                a.close();
            } finally {
                slow.countDown();
            }
        }
        assertThat(leases.holderOf(id)).contains("node-a");
        assertThat(store.load(id).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);

        assertThat(nodeB.start().recoverInterruptedCount()).as("another node waits for the lease to expire").isZero();
        try (EngineHarness restarted = new EngineHarness(store, audit).config(b -> b.nodeId("node-a").clock(clock)
                .leaseProvider(leases).leaseTtl(TTL))) {
            OrchestrationEngine a = restarted.start();
            assertThat(a.recoverInterruptedCount()).isEqualTo(1);
            Run recovered = a.get(id);
            assertThat(recovered.status()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(recovered.haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);
        }
        ChainVerification chain = AuditChain.verify(events(id));
        assertThat(chain.valid()).as(chain.summary()).isTrue();
    }

    /** Counts the commits of one node (the store itself is shared). */
    private static RunStore counting(RunStore store, AtomicInteger commits) {
        return new RunStore() {
            @Override
            public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
                commits.incrementAndGet();
                store.commit(run, events, auditSink);
            }

            @Override
            public void save(Run run) {
                store.save(run);
            }

            @Override
            public Optional<Run> load(String runId) {
                return store.load(runId);
            }

            @Override
            public List<Run> loadAll() {
                return store.loadAll();
            }
        };
    }

    @Test
    void aNodeWhoseLeaseExpiredAndWasTakenOverNeverCommitsTheResultOfItsAttempt() {
        AtomicInteger commitsOfA = new AtomicInteger();
        CountDownLatch slow = new CountDownLatch(1);
        nodeA.storeDecorator(s -> counting(s, commitsOfA)).agent(blockingImplementation(slow));
        OrchestrationEngine a = nodeA.start();
        OrchestrationEngine b = nodeB.start();
        String id = a.start(REQUIREMENT, "takeover", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        nodeA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);

        clock.advance(TTL.plusSeconds(1)); // node A's lease expires (its next heartbeat is 20 s of real time away)
        assertThat(b.recoverInterruptedCount()).as("node B takes the run over and commits").isEqualTo(1);
        List<AuditEvent> afterTakeover = events(id);
        int commitsBefore = commitsOfA.get();

        slow.countDown(); // node A's attempt finishes only now
        await().atMost(10, TimeUnit.SECONDS).until(() -> !a.isCached(id));
        await().during(300, TimeUnit.MILLISECONDS).atMost(10, TimeUnit.SECONDS)
                .until(() -> events(id).size() == afterTakeover.size());
        assertThat(commitsOfA.get()).as("node A committed nothing after losing its lease").isEqualTo(commitsBefore);
        assertThat(events(id)).isEqualTo(afterTakeover);
        assertThat(leases.holderOf(id)).isEmpty();
        ChainVerification chain = AuditChain.verify(events(id));
        assertThat(chain.valid()).as(chain.summary()).isTrue();

        b.resume(id, ALICE);
        nodeB.awaitApproval(id, RELEASE);
        b.approve(id, RELEASE, ALICE, "shipped by node b");
        nodeB.awaitStatus(id, RunStatus.COMPLETED);
        assertThat(nodesOf(events(id), AuditEventType.STAGE_STARTED, IMPLEMENT)).containsExactly("node-a", "node-b");
        ChainVerification done = AuditChain.verify(events(id));
        assertThat(done.valid()).as(done.summary()).isTrue();
    }

    @Test
    void aLeaseTakenOverBetweenTheMutationAndItsCommitWritesNothing() {
        AtomicInteger commitsOfA = new AtomicInteger();
        FlakyLeases stealing = new FlakyLeases(leases);
        try (EngineHarness stolenA = new EngineHarness(store, audit).storeDecorator(s -> counting(s, commitsOfA))
                .config(b -> b.nodeId("node-a").clock(clock).leaseProvider(stealing).leaseTtl(TTL))) {
            OrchestrationEngine a = stolenA.start();
            String id = a.start(REQUIREMENT, "stolen", RunOptions.defaults()).id();
            stolenA.awaitApproval(id, DESIGN);
            List<AuditEvent> before = events(id);
            int commitsBefore = commitsOfA.get();

            stealing.stealOnRenewFor = "node-b";
            assertThatThrownBy(() -> a.approve(id, DESIGN, ALICE, "approved on node a"))
                    .isInstanceOfSatisfying(RunLeasedElsewhereException.class,
                            e -> assertThat(e.holder()).isEqualTo("node-b"));
            assertThat(a.isCached(id)).as("the run is abandoned on node a").isFalse();
            await().during(300, TimeUnit.MILLISECONDS).atMost(10, TimeUnit.SECONDS)
                    .until(() -> events(id).size() == before.size());
            assertThat(commitsOfA.get()).as("neither the approval nor the attempts it started were committed")
                    .isEqualTo(commitsBefore);
            assertThat(store.load(id).orElseThrow().stage(DESIGN).status()).isEqualTo(StageStatus.AWAITING_APPROVAL);

            OrchestrationEngine b = nodeB.start();
            b.approve(id, DESIGN, ALICE, "approved on node b");
            nodeB.awaitApproval(id, RELEASE);
            ChainVerification chain = AuditChain.verify(events(id));
            assertThat(chain.valid()).as(chain.summary()).isTrue();
        }
    }

    @Test
    void anAttemptWhoseHeartbeatFindsTheLeaseLostIsInterruptedAndNothingIsWritten() {
        AtomicInteger commitsOfA = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch never = new CountDownLatch(1);
        FlakyLeases losing = new FlakyLeases(leases);
        try (EngineHarness lostA = new EngineHarness(store, audit).storeDecorator(s -> counting(s, commitsOfA))
                .config(b -> b.nodeId("node-a").clock(clock).leaseProvider(losing)
                        .leaseTtl(Duration.ofMillis(300)))) {
            lostA.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
                try {
                    never.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
                return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "too late");
            }));
            OrchestrationEngine a = lostA.start();
            String id = a.start(REQUIREMENT, "heartbeat-lost", RunOptions.defaults()
                    .withAutonomy(AutonomyLevel.FULL_AUTO)).id();
            lostA.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
            List<AuditEvent> before = events(id);
            int commitsBefore = commitsOfA.get();

            losing.failRenewals = true; // the next heartbeat (every 100 ms) finds the lease lost
            await().atMost(10, TimeUnit.SECONDS).until(() -> !a.isCached(id) && interrupted.get());
            await().during(300, TimeUnit.MILLISECONDS).atMost(10, TimeUnit.SECONDS)
                    .until(() -> events(id).size() == before.size());
            assertThat(commitsOfA.get()).as("LEASE_LOST is an interruption: nothing applied, nothing retried")
                    .isEqualTo(commitsBefore);
            assertThat(store.load(id).orElseThrow().stage(IMPLEMENT).status()).isEqualTo(StageStatus.RUNNING);
        } finally {
            never.countDown();
        }
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
