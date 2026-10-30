package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.RejectDisposition;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunSnapshot;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.port.RunStore;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * Fixes from the adversarial review of the engine: exit gate before acceptance, secret scanning of string values,
 * gated verification reports, approvals bound to what was reviewed, atomic commit SPI, typed state exceptions,
 * deterministic risk floor and robust retry wake-ups.
 */
class OrchestrationEngineReviewFixesTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";
    private static final String DESIGN = WorkflowDefinitions.DESIGN;
    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;
    private static final String VERIFY = WorkflowDefinitions.VERIFY;
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;
    private static final String FEATURE = "url-shortener/src/main/java/com/example/Feature.java";

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    private static RunOptions options(AutonomyLevel autonomy) {
        return RunOptions.defaults().withAutonomy(autonomy);
    }

    private static DesignSpec design(String overview, RiskLevel risk, List<DesignSpec.DataModelChange> changes) {
        DesignSpec base = Payloads.design(risk);
        return new DesignSpec(overview, base.components(), base.apiChanges(), changes, base.sequenceFlows(),
                base.nfrStrategies(), base.securityConsiderations(), base.dataHandling(), base.alternativesConsidered(),
                base.risk(), base.decisions());
    }

    private List<AuditEvent> events(String runId, AuditEventType type, String stageId) {
        return harness.events(runId).stream().filter(e -> e.type() == type && stageId.equals(e.stageId())).toList();
    }

    // ------------------------------------------------------------------ F1 exit gate before acceptance

    @Test
    void exitApprovalAcceptsTheArtifactOnlyWhenGrantedAndReworkNeverAcceptsTheRejectedVersion() {
        harness.agent(new FakeAgent(AgentRole.ARCHITECTURE, (ctx, call) -> StageResult.success(
                design("Overview v" + call, RiskLevel.MEDIUM, List.of()), "design " + call)));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "accept-after-approval", options(AutonomyLevel.GUIDED)).id();

        Run waiting = harness.awaitApproval(id, DESIGN);
        Approval first = waiting.stage(DESIGN).pendingApproval();
        assertThat(first.artifactVersion()).isEqualTo(1);
        assertThat(first.previousAcceptedVersion()).isNull();
        assertThat(waiting.acceptedArtifact(DESIGN)).isEmpty();
        assertThat(events(id, AuditEventType.ARTIFACT_ACCEPTED, DESIGN)).isEmpty();

        engine.reject(id, DESIGN, ALICE, "add a cache alternative", RejectDisposition.REWORK);
        Run again = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                && r.stage(DESIGN).status() == StageStatus.AWAITING_APPROVAL && r.approvals().size() == 2);
        Approval second = again.stage(DESIGN).pendingApproval();
        assertThat(second.artifactVersion()).isEqualTo(2);
        assertThat(again.acceptedArtifact(DESIGN)).as("the rejected v1 was never accepted").isEmpty();
        assertThat(again.artifacts().stream().filter(a -> a.stageId().equals(DESIGN)).map(Artifact::version))
                .containsExactly(1, 2);

        Run approved = engine.approve(id, DESIGN, ALICE, "better", second.id());
        assertThat(approved.acceptedVersions()).containsEntry(DESIGN, 2);
        assertThat(approved.acceptedArtifact(DESIGN).orElseThrow().hash()).isEqualTo(second.artifactHash());
        AuditEvent accepted = events(id, AuditEventType.ARTIFACT_ACCEPTED, DESIGN).getFirst();
        assertThat(events(id, AuditEventType.ARTIFACT_ACCEPTED, DESIGN)).hasSize(1);
        assertThat(accepted.data()).containsEntry("approvalId", second.id()).containsEntry("version", 2);
        AuditEvent granted = events(id, AuditEventType.APPROVAL_GRANTED, DESIGN).getFirst();
        assertThat(accepted.seq()).isGreaterThan(granted.seq());
        assertThat(granted.data()).containsEntry("artifactHash", second.artifactHash());
        harness.awaitApproval(id, RELEASE);
    }

    @Test
    void theVersionOfADeniedArtifactIsNeverReusedAndTheCounterIsPersisted() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(call == 1
                ? Payloads.changeSet(FEATURE, "String key = \"AKIAABCDEFGHIJKLMNOP\";")
                : Payloads.changeSet(FEATURE, "class Feature {}"), "implementation " + call)));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "versions", options(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.artifacts()).noneMatch(a -> a.stageId().equals(IMPLEMENT));
        assertThat(stopped.lastArtifactVersions()).containsEntry(IMPLEMENT, 1);
        JsonCodec codec = JsonCodec.standard();
        Run restored = Run.fromSnapshot(codec.fromJson(codec.toJson(stopped.toSnapshot()), RunSnapshot.class),
                new WorkflowGraph(WorkflowDefinitions.defaultSdlc()));
        assertThat(restored.lastArtifactVersions()).isEqualTo(stopped.lastArtifactVersions());
        assertThat(restored.copy().nextArtifactVersion(IMPLEMENT)).isEqualTo(2);

        engine.replan(id, ALICE, REQUIREMENT + " Keep the keys out of the code.");
        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.acceptedVersions()).containsEntry(IMPLEMENT, 2);
        assertThat(atRelease.artifacts().stream().filter(a -> a.stageId().equals(IMPLEMENT)).map(Artifact::version))
                .containsExactly(2);
    }

    // ------------------------------------------------------------------ F2 secrets in string values, rationale

    @Test
    void aQuotedPasswordLiteralIsDeniedAtTheExitGateAndRemovedFromHistory() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.changeSet(FEATURE, "class Feature {\n    String password = \"S3cretPass!\";\n}"),
                "implementation with a hard-coded password")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "password", options(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.stage(IMPLEMENT).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage(IMPLEMENT).lastError()).contains("SEC-002").contains("password assignment");
        assertThat(stopped.artifacts()).noneMatch(a -> a.stageId().equals(IMPLEMENT));
        assertThat(harness.events(id)).noneMatch(e -> e.message().contains("S3cretPass!"));
        assertThat(events(id, AuditEventType.POLICY_DENIED, IMPLEMENT)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("removed", true));
    }

    @Test
    void codeThatOnlyAssignsAPasswordVariablePassesTheExitGate() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.changeSet(FEATURE, "class Feature {\n    private final String password;\n"
                        + "    Feature(String password) {\n        this.password = password;\n    }\n"
                        + "    String token(Request request) {\n"
                        + "        String token = request.getHeader(\"Authorization\");\n        return token;\n"
                        + "    }\n}"), "clean implementation")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "clean-password", options(AutonomyLevel.FULL_AUTO)).id();

        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.stage(IMPLEMENT).status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(atRelease.policyFindings()).noneMatch(f -> f.ruleId().equals("SEC-002"));
    }

    @Test
    void aRationaleContainingACredentialIsRedactedBeforeItIsAudited() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.forRole(AgentRole.IMPLEMENTATION), "pushed with api_key=AbC123xyz789 to the sandbox")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "rationale", options(AutonomyLevel.FULL_AUTO)).id();

        harness.awaitApproval(id, RELEASE);
        assertThat(events(id, AuditEventType.AGENT_DECISION, IMPLEMENT)).singleElement()
                .satisfies(e -> assertThat(e.message()).isEqualTo(OrchestrationEngine.REDACTED_RATIONALE));
        assertThat(harness.events(id)).noneMatch(e -> e.message().contains("AbC123xyz789"));
    }

    // ------------------------------------------------------------------ F3 failed verification reports are gated

    @Test
    void aFailedVerificationReportThatLeaksASecretIsDeniedInsteadOfFeedingBack() {
        FakeAgent implement = FakeAgent.succeeding(AgentRole.IMPLEMENTATION);
        VerificationReport leaky = new VerificationReport(
                List.of(Check.fail("no-hard-coded-credentials", "found AKIAABCDEFGHIJKLMNOP in Feature.java")),
                new VerificationReport.Traceability(1, 1, List.of()), null, false,
                List.of("Remove the access key AKIAABCDEFGHIJKLMNOP from Feature.java"));
        harness.agent(implement).agent(new FakeAgent(AgentRole.VERIFICATION, (ctx, call) ->
                StageResult.success(leaky, "verification failed")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "leaky-verification", options(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.stage(VERIFY).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage(VERIFY).lastError()).contains("SEC-002");
        assertThat(stopped.artifacts()).noneMatch(a -> a.stageId().equals(VERIFY));
        assertThat(stopped.counters().feedbackLoops()).isZero();
        assertThat(implement.calls()).isEqualTo(1);
        assertThat(stopped.stage(IMPLEMENT).feedback()).isEmpty();
        assertThat(harness.eventTypes(id)).doesNotContain(AuditEventType.FEEDBACK_LOOP);
    }

    // ------------------------------------------------------------------ F4 approvals bound to the reviewed approval

    @Test
    void anApprovalIsBoundToTheApprovalTheOperatorReviewed() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "bound", options(AutonomyLevel.GUIDED)).id();
        Approval stale = harness.awaitApproval(id, DESIGN).stage(DESIGN).pendingApproval();
        engine.replan(id, ALICE, REQUIREMENT + " Support custom aliases.");
        Approval fresh = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                && r.stage(DESIGN).status() == StageStatus.AWAITING_APPROVAL
                && !r.stage(DESIGN).pendingApproval().id().equals(stale.id())).stage(DESIGN).pendingApproval();

        assertThatThrownBy(() -> engine.approve(id, DESIGN, ALICE, "ok", stale.id()))
                .isExactlyInstanceOf(InvalidRunStateException.class)
                .hasMessage("approval " + stale.id() + " is no longer pending; the stage now awaits approval "
                        + fresh.id());
        assertThatThrownBy(() -> engine.reject(id, DESIGN, ALICE, "no", RejectDisposition.ABORT, stale.id()))
                .isInstanceOf(InvalidRunStateException.class);
        assertThat(engine.get(id).status()).isEqualTo(RunStatus.WAITING_HUMAN);
        assertThat(engine.get(id).stage(DESIGN).pendingApproval().id()).isEqualTo(fresh.id());

        Run approved = engine.approve(id, DESIGN, ALICE, "ok", fresh.id());
        String designHash = approved.acceptedArtifact(DESIGN).orElseThrow().hash();
        assertThat(approved.approvals()).filteredOn(a -> a.id().equals(fresh.id())).singleElement()
                .satisfies(a -> {
                    assertThat(a.status()).isEqualTo(ApprovalStatus.APPROVED);
                    assertThat(a.artifactHash()).isEqualTo(designHash);
                });

        Approval release = harness.awaitApproval(id, RELEASE).stage(RELEASE).pendingApproval();
        engine.approve(id, RELEASE, ALICE, "ship it", release.id());
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);
        ReleaseReadiness record = done.acceptedArtifact(RELEASE).orElseThrow().payloadAs(ReleaseReadiness.class);
        assertThat(record.changeRecord().approvals()).extracting(a -> a.stageId() + "=" + a.artifactHash())
                .containsExactly(DESIGN + "=" + designHash, RELEASE + "=" + release.artifactHash());
    }

    // ------------------------------------------------------------------ F5 commit SPI, no ghost runs

    /** A store that fails a configurable number of commits; counts the events it committed. */
    private static final class FlakyStore implements RunStore {
        private final RunStore delegate;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger committedEvents = new AtomicInteger();

        FlakyStore(RunStore delegate) {
            this.delegate = delegate;
        }

        void failNextCommits(int count) {
            failures.set(count);
        }

        @Override
        public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("database unavailable");
            }
            RunStore.super.commit(run, events, auditSink);
            committedEvents.addAndGet(events.size());
        }

        @Override
        public void save(Run run) {
            delegate.save(run);
        }

        @Override
        public java.util.Optional<Run> load(String id) {
            return delegate.load(id);
        }

        @Override
        public List<Run> loadAll() {
            return delegate.loadAll();
        }
    }

    @Test
    void aStartWhoseFirstCommitFailsLeavesNoGhostRun() {
        FlakyStore store = new FlakyStore(harness.store);
        harness.storeDecorator(s -> store);
        OrchestrationEngine engine = harness.start();
        store.failNextCommits(1);

        assertThatThrownBy(() -> engine.start(REQUIREMENT, "ghost", options(AutonomyLevel.FULL_AUTO)))
                .hasMessageContaining("database unavailable");
        await().during(Duration.ofMillis(300)).atMost(EngineHarness.TIMEOUT)
                .until(() -> engine.list().isEmpty() && harness.store.loadAll().isEmpty());

        String id = engine.start(REQUIREMENT, "after-ghost", options(AutonomyLevel.FULL_AUTO)).id();
        harness.awaitApproval(id, RELEASE);
        assertThat(engine.list()).extracting(Run::id).containsExactly(id);
        assertThat(store.committedEvents.get()).as("the engine persists through RunStore.commit")
                .isEqualTo(harness.events(id).size());
    }

    @Test
    void aCommitFailureOnALaterCommandReachesTheCallerAndTheStoreCatchesUpLater() {
        FlakyStore store = new FlakyStore(harness.store);
        harness.storeDecorator(s -> store);
        CountDownLatch hold = new CountDownLatch(1);
        for (AgentRole role : List.of(AgentRole.IMPLEMENTATION, AgentRole.TEST_DESIGN)) {
            // nothing else commits while the assertions about the stale store run
            harness.agent(new FakeAgent(role, (ctx, call) -> {
                hold.await(10, TimeUnit.SECONDS);
                return StageResult.success(Payloads.forRole(role), "done");
            }));
        }
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "later-failure", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);
        try {
            store.failNextCommits(1);
            assertThatThrownBy(() -> engine.approve(id, DESIGN, ALICE, "ok"))
                    .hasMessageContaining("database unavailable");
            assertThat(engine.get(id).stage(DESIGN).status()).as("in-memory state is ahead of the store")
                    .isEqualTo(StageStatus.SUCCEEDED);
            assertThat(harness.store.load(id).orElseThrow().stage(DESIGN).status())
                    .isEqualTo(StageStatus.AWAITING_APPROVAL);
        } finally {
            hold.countDown();
        }
        harness.awaitApproval(id, RELEASE);
        await().atMost(EngineHarness.TIMEOUT).until(() ->
                harness.store.load(id).orElseThrow().stage(DESIGN).status() == StageStatus.SUCCEEDED);
    }

    // ------------------------------------------------------------------ F6 typed state exception

    @Test
    void commandsInTheWrongStateThrowInvalidRunStateException() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "states", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);

        assertThatThrownBy(() -> engine.approve(id, IMPLEMENT, ALICE, null))
                .isExactlyInstanceOf(InvalidRunStateException.class).hasMessageContaining("not awaiting approval");
        assertThatThrownBy(() -> engine.clarify(id, ALICE, Map.of())).isExactlyInstanceOf(InvalidRunStateException.class);
        assertThatThrownBy(() -> engine.resume(id, ALICE)).isExactlyInstanceOf(InvalidRunStateException.class);
        engine.stop(id, ALICE, "pause");
        assertThatThrownBy(() -> engine.stop(id, ALICE, "again")).isExactlyInstanceOf(InvalidRunStateException.class);
        assertThatThrownBy(() -> engine.approve(id, DESIGN, ALICE, null))
                .isExactlyInstanceOf(InvalidRunStateException.class).hasMessageContaining("SAFE_STOPPED");
        engine.resume(id, ALICE);
        engine.reject(id, DESIGN, ALICE, "out of scope", RejectDisposition.ABORT);
        assertThatThrownBy(() -> engine.replan(id, ALICE, "anything"))
                .isExactlyInstanceOf(InvalidRunStateException.class);
        assertThatThrownBy(() -> engine.approve(id, DESIGN, ALICE, null)).isInstanceOf(IllegalStateException.class)
                .isInstanceOf(InvalidRunStateException.class);

        engine.close();
        assertThatThrownBy(() -> engine.start(REQUIREMENT, "closed", RunOptions.defaults()))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(InvalidRunStateException.class);
    }

    // ------------------------------------------------------------------ F7 deterministic risk floor

    @Test
    void aDesignThatUnderReportsItsRiskIsStillGatedByTheEngineRiskFloor() {
        DesignSpec underReported = design("Drop the legacy code column", RiskLevel.LOW,
                List.of(new DesignSpec.DataModelChange("links", "drop column legacy_code", "V2__drop_legacy.sql",
                        false)));
        harness.agent(new FakeAgent(AgentRole.ARCHITECTURE, (ctx, call) -> StageResult.success(underReported,
                "low risk, trust me")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "risk-floor", options(AutonomyLevel.GUIDED)).id();

        Run waiting = harness.awaitApproval(id, DESIGN);
        Approval approval = waiting.stage(DESIGN).pendingApproval();
        assertThat(approval.ruleIds()).contains("GOV-001");
        assertThat(approval.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(approval.reason()).contains("HIGH")
                .contains("engine: non-backward-compatible data model change (links)");

        Run approved = engine.approve(id, DESIGN, ALICE, "migration reviewed");
        RiskAssessment risk = approved.currentRisk();
        assertThat(risk.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(risk.factors()).contains("engine: non-backward-compatible data model change (links)");
        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.stage(IMPLEMENT).status()).as("the HIGH risk was accepted with the design")
                .isEqualTo(StageStatus.SUCCEEDED);
    }

    // ------------------------------------------------------------------ F8 retry wake-ups

    /** A clock that fails a configurable number of reads (stands in for any failure inside a wake-up). */
    private static final class FlakyClock extends Clock {
        private final AtomicInteger failures = new AtomicInteger();

        void failNextReads(int count) {
            failures.set(count);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("clock unavailable");
            }
            return Instant.now();
        }
    }

    /**
     * Runs a FULL_AUTO run whose implement attempt fails once (retry scheduled on a held scheduler), arms the
     * failure while nothing else runs, then releases the wake-up.
     */
    private String runUntilTheRetryWakeUp(OrchestrationEngine engine, CountDownLatch schedulerHeld, Runnable arm) {
        String id = engine.start(REQUIREMENT, "wake-up", options(AutonomyLevel.FULL_AUTO)
                .withFaults(List.of(new FaultSpec(IMPLEMENT, 1, FaultKind.TRANSIENT)))).id();
        harness.awaitRun(id, r -> r.stage(IMPLEMENT).status() == StageStatus.RETRY_SCHEDULED
                && r.stage(WorkflowDefinitions.TEST_DESIGN).status() == StageStatus.SUCCEEDED);
        arm.run();
        schedulerHeld.countDown();
        return id;
    }

    private ScheduledExecutorService heldScheduler(CountDownLatch held) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.submit(() -> held.await(10, TimeUnit.SECONDS));
        harness.config(b -> b.retryScheduler(scheduler));
        return scheduler;
    }

    @Test
    void aStoreFailureOnTheRetryWakeUpDoesNotStrandTheStage() {
        FlakyStore store = new FlakyStore(harness.store);
        harness.storeDecorator(s -> store);
        CountDownLatch held = new CountDownLatch(1);
        ScheduledExecutorService scheduler = heldScheduler(held);
        try {
            OrchestrationEngine engine = harness.start();
            String id = runUntilTheRetryWakeUp(engine, held, () -> store.failNextCommits(1));

            Run atRelease = harness.awaitApproval(id, RELEASE);
            assertThat(atRelease.stage(IMPLEMENT).status()).isEqualTo(StageStatus.SUCCEEDED);
            assertThat(atRelease.stage(IMPLEMENT).attempts()).isEqualTo(2);
            await().atMost(EngineHarness.TIMEOUT).until(() ->
                    harness.store.load(id).orElseThrow().stage(IMPLEMENT).status() == StageStatus.SUCCEEDED);
        } finally {
            held.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void aFailedRetryWakeUpIsRetriedWithTheSameBackoff() {
        FlakyClock clock = new FlakyClock();
        harness.config(b -> b.clock(clock));
        CountDownLatch held = new CountDownLatch(1);
        ScheduledExecutorService scheduler = heldScheduler(held);
        try {
            OrchestrationEngine engine = harness.start();
            String id = runUntilTheRetryWakeUp(engine, held, () -> clock.failNextReads(1));

            Run atRelease = harness.awaitApproval(id, RELEASE);
            assertThat(atRelease.stage(IMPLEMENT).status()).isEqualTo(StageStatus.SUCCEEDED);
            assertThat(atRelease.stage(IMPLEMENT).attempts()).isEqualTo(2);
        } finally {
            held.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void aRetryWakeUpThatKeepsFailingFailsTheStageInsteadOfStrandingIt() {
        FlakyClock clock = new FlakyClock();
        harness.config(b -> b.clock(clock));
        CountDownLatch held = new CountDownLatch(1);
        ScheduledExecutorService scheduler = heldScheduler(held);
        try {
            OrchestrationEngine engine = harness.start();
            String id = runUntilTheRetryWakeUp(engine, held,
                    () -> clock.failNextReads(OrchestrationEngine.MAX_WAKE_UP_RETRIES + 1));

            Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
            assertThat(stopped.haltReason()).isEqualTo(HaltReason.AGENT_FAILURE_EXHAUSTED);
            assertThat(stopped.stage(IMPLEMENT).status()).isEqualTo(StageStatus.FAILED);
            assertThat(stopped.stage(IMPLEMENT).lastError()).contains("retry wake-up failed 4 times")
                    .contains("clock unavailable");
            assertThat(stopped.stage(IMPLEMENT).attempts()).isEqualTo(1);
        } finally {
            held.countDown();
            scheduler.shutdownNow();
        }
    }
}
