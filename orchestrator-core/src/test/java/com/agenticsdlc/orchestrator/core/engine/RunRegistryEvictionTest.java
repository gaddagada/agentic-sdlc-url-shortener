package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.MutableClock;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * Bounded retention of the in-memory run registry: idle runs are evicted by age, terminal runs by count, and evicted
 * runs stay readable and continue correctly when a command loads them again.
 */
class RunRegistryEvictionTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";
    private static final String DESIGN = WorkflowDefinitions.DESIGN;
    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final EngineHarness harness = new EngineHarness().config(b -> b.clock(clock));

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void aRunWaitingLongerThanTheRetentionIsEvictedAndItsApprovalContinuesCorrectly() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "aged", RunOptions.defaults()).id();
        Run waiting = harness.awaitApproval(id, DESIGN);
        assertThat(engine.isCached(id)).isTrue();

        clock.advance(Duration.ofMinutes(59));
        assertThat(engine.evictIdleRuns()).isZero();
        clock.advance(Duration.ofMinutes(2));
        assertThat(engine.evictIdleRuns()).isEqualTo(1);
        assertThat(engine.isCached(id)).isFalse();

        Run fromStore = engine.get(id);
        assertThat(fromStore.stage(DESIGN).status()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(fromStore.stage(DESIGN).pendingApproval()).isEqualTo(waiting.stage(DESIGN).pendingApproval());
        assertThat(engine.isCached(id)).as("reads do not rehydrate").isFalse();
        assertThat(engine.list()).extracting(Run::id).containsExactly(id);

        String approvalId = fromStore.stage(DESIGN).pendingApproval().id();
        engine.approve(id, DESIGN, ALICE, "approved after eviction", approvalId);
        assertThat(engine.isCached(id)).isTrue();
        harness.awaitApproval(id, RELEASE);
        engine.approve(id, RELEASE, ALICE, null);
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        assertThat(done.approvals()).extracting(a -> a.stageId() + ":" + a.decidedBy())
                .containsExactly(DESIGN + ":alice", RELEASE + ":alice");
        assertThat(done.humanWaitMs(clock.instant())).as("the wait before eviction is still accounted")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(61).toMillis());
        assertThat(AuditChain.verify(harness.events(id)).valid()).isTrue();
        assertThat(harness.events(id)).hasSize((int) done.auditSeq());
    }

    @Test
    void terminalRunsBeyondTheMaximumAreEvictedLeastRecentlyEndedFirst() {
        harness.config(b -> b.registryMaxTerminalRuns(2));
        OrchestrationEngine engine = harness.start();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = engine.start(REQUIREMENT, "count-" + i, RunOptions.defaults()
                    .withAutonomy(AutonomyLevel.FULL_AUTO)).id();
            harness.awaitApproval(id, RELEASE);
            ids.add(id);
        }
        for (String id : ids) {
            clock.advance(Duration.ofMinutes(1));
            engine.approve(id, RELEASE, ALICE, null);
            assertThat(engine.get(id).status()).isEqualTo(RunStatus.COMPLETED);
        }

        assertThat(ids).extracting(engine::isCached).containsExactly(false, false, true, true);
        assertThat(engine.list()).extracting(Run::id).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(engine.get(ids.getFirst()).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(engine.isCached(ids.getFirst())).isFalse();
        assertThatThrownBy(() -> engine.replan(ids.getFirst(), ALICE, "changed"))
                .as("a command on an evicted run loads it and validates it like any other run")
                .isInstanceOf(InvalidRunStateException.class);
        assertThatThrownBy(() -> engine.get("unknown")).isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void runsExecutingOnThisNodeAreNeverEvicted() {
        CountDownLatch slow = new CountDownLatch(1);
        harness.config(b -> b.registryRetention(Duration.ZERO)).agent(new FakeAgent(AgentRole.IMPLEMENTATION,
                (ctx, call) -> {
                    slow.await(10, TimeUnit.SECONDS);
                    return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "slow");
                }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "busy", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO))
                .id();
        harness.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
        try {
            clock.advance(Duration.ofMinutes(30)); // within the active-time budget
            assertThat(engine.evictIdleRuns()).isZero();
            assertThat(engine.isCached(id)).isTrue();
        } finally {
            slow.countDown();
        }

        harness.awaitApproval(id, RELEASE);
        // evicted right after it started waiting (retention zero)
        await().atMost(EngineHarness.TIMEOUT).until(() -> !engine.isCached(id));
        engine.approve(id, RELEASE, ALICE, null);
        assertThat(engine.get(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(engine.isCached(id)).isFalse();
        assertThat(harness.store.load(id).orElseThrow().status()).as("eviction never loses data")
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(AuditChain.verify(harness.events(id)).valid()).isTrue();
    }
}
