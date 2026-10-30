package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.TEST_UNDO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.metrics.MetricsCalculator;
import com.agenticsdlc.orchestrator.core.metrics.ReliabilityMetrics;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalKind;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.CompensationStatus;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.RejectDisposition;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunBudget;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.MutableClock;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class OrchestrationEngineTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";
    private static final String INTAKE = WorkflowDefinitions.INTAKE;
    private static final String DESIGN = WorkflowDefinitions.DESIGN;
    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;
    private static final String TEST_DESIGN = WorkflowDefinitions.TEST_DESIGN;
    private static final String VERIFY = WorkflowDefinitions.VERIFY;
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void closeEngine() {
        harness.close();
    }

    private static RunOptions options(AutonomyLevel autonomy) {
        return RunOptions.defaults().withAutonomy(autonomy);
    }

    private Run completeRelease(OrchestrationEngine engine, String runId) {
        harness.awaitApproval(runId, RELEASE);
        engine.approve(runId, RELEASE, ALICE, "ship it");
        return harness.awaitStatus(runId, RunStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ gates per autonomy level

    @Test
    void guidedHappyPathPausesAtDesignAndReleaseThenCompletes() {
        OrchestrationEngine engine = harness.start();
        Run run = engine.start(REQUIREMENT, "greenfield", options(AutonomyLevel.GUIDED));
        String id = run.id();

        Run waiting = harness.awaitApproval(id, DESIGN);
        assertThat(waiting.pendingApprovals()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo(ApprovalKind.EXIT);
            assertThat(a.ruleIds()).contains("GOV-001");
            assertThat(a.reason()).contains("GOV-001").contains("MEDIUM");
        });
        assertThat(waiting.stage(IMPLEMENT).status()).isEqualTo(StageStatus.PENDING);
        assertThat(waiting.acceptedArtifact(DESIGN).orElseThrow().provenance().derivedFrom())
                .containsOnlyKeys(INTAKE, WorkflowDefinitions.DECOMPOSE, WorkflowDefinitions.IMPACT);

        engine.approve(id, DESIGN, ALICE, "looks good");
        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.stage(IMPLEMENT).status())
                .as("implement is RISK_BASED but the MEDIUM risk was already accepted with the design")
                .isEqualTo(StageStatus.SUCCEEDED);
        assertThat(atRelease.pendingApprovals()).singleElement()
                .satisfies(a -> assertThat(a.ruleIds()).contains("CHG-002"));

        engine.approve(id, RELEASE, ALICE, "ship it");
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);
        assertThat(done.stages()).allSatisfy(s -> assertThat(s.status()).isEqualTo(StageStatus.SUCCEEDED));
        assertThat(done.endedAt()).isNotNull();
        assertThat(done.acceptedVersions()).hasSize(10);
        assertThat(done.artifacts()).hasSize(11);
        assertThat(done.counters().approvalsRequested()).isEqualTo(2);
        assertThat(done.counters().approvalsApproved()).isEqualTo(2);
        ReleaseReadiness release = done.acceptedArtifact(RELEASE).orElseThrow().payloadAs(ReleaseReadiness.class);
        assertThat(release.changeRecord().approvals()).extracting(ReleaseReadiness.ApprovalRecord::stageId)
                .containsExactly(DESIGN, RELEASE);
        assertThat(release.changeRecord().approvals()).allSatisfy(a -> assertThat(a.approver()).isEqualTo("alice"));
        assertThat(release.changeRecord().policyVerdict()).contains("alice");
        assertThat(done.acceptedArtifact(RELEASE).orElseThrow().provenance().agentId()).isEqualTo("engine");
        assertThat(done.humanWaitIntervals()).hasSize(2).allSatisfy(i -> assertThat(i.open()).isFalse());

        List<AuditEvent> events = harness.events(id);
        assertThat(events).extracting(AuditEvent::seq)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, events.size()).boxed().toList());
        assertThat(harness.eventTypes(id)).contains(AuditEventType.RUN_CREATED, AuditEventType.CHECKPOINT_CREATED,
                AuditEventType.GATE_EVALUATED, AuditEventType.APPROVAL_REQUESTED, AuditEventType.APPROVAL_GRANTED,
                AuditEventType.ARTIFACT_ACCEPTED, AuditEventType.RUN_COMPLETED);
        assertThat(events.stream().filter(e -> e.type() == AuditEventType.APPROVAL_GRANTED))
                .allSatisfy(e -> assertThat(e.actor()).isEqualTo(ALICE));
        assertThat(harness.store.load(id)).get().extracting(Run::status).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void fullAutoOnlyPausesAtTheReleaseGate() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "greenfield", options(AutonomyLevel.FULL_AUTO)).id();

        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.approvals()).singleElement().satisfies(a -> {
            assertThat(a.stageId()).isEqualTo(RELEASE);
            assertThat(a.ruleIds()).contains("CHG-002", "GOV-003");
        });
        assertThat(completeRelease(engine, id).counters().approvalsRequested()).isEqualTo(1);
    }

    @Test
    void strictRequiresApprovalAtEveryStageEntryExceptIntake() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "strict", options(AutonomyLevel.STRICT)).id();
        for (int round = 0; round < 30; round++) {
            Run run = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                    || r.status() == RunStatus.COMPLETED);
            if (run.status() == RunStatus.COMPLETED) {
                break;
            }
            for (Approval pending : run.pendingApprovals()) {
                engine.approve(id, pending.stageId(), ALICE, null);
            }
        }
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);
        List<String> entryStages = done.approvals().stream().filter(a -> a.kind() == ApprovalKind.ENTRY)
                .map(Approval::stageId).toList();
        assertThat(entryStages).containsExactlyInAnyOrder("decompose", "impact", DESIGN, IMPLEMENT, TEST_DESIGN, VERIFY,
                "document", "security-scan", RELEASE);
        assertThat(done.approvals().stream().filter(a -> a.kind() == ApprovalKind.EXIT).map(Approval::stageId))
                .containsExactlyInAnyOrder(DESIGN, IMPLEMENT, RELEASE);
        assertThat(done.approvals()).allSatisfy(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.APPROVED));
    }

    // ------------------------------------------------------------------ parallelism and joins

    @Test
    void parallelBranchesOverlapAndTheJoinWaitsForBoth() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicLong implementDone = new AtomicLong();
        AtomicLong testDesignDone = new AtomicLong();
        AtomicLong verifyStarted = new AtomicLong();
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            bothStarted.countDown();
            if (!bothStarted.await(10, TimeUnit.SECONDS)) {
                throw StageFailure.fatal("test-design did not run concurrently");
            }
            implementDone.set(System.nanoTime());
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "parallel");
        }));
        harness.agent(new FakeAgent(AgentRole.TEST_DESIGN, (ctx, call) -> {
            bothStarted.countDown();
            if (!bothStarted.await(10, TimeUnit.SECONDS)) {
                throw StageFailure.fatal("implement did not run concurrently");
            }
            testDesignDone.set(System.nanoTime());
            return StageResult.success(Payloads.forRole(AgentRole.TEST_DESIGN), "parallel");
        }));
        FakeAgent verify = new FakeAgent(AgentRole.VERIFICATION, (ctx, call) -> {
            verifyStarted.set(System.nanoTime());
            return StageResult.success(Payloads.verification(true), "verified");
        });
        harness.agent(verify);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "parallel", options(AutonomyLevel.FULL_AUTO)).id();

        Run run = harness.awaitApproval(id, RELEASE);
        assertThat(bothStarted.getCount()).isZero();
        assertThat(verifyStarted.get()).isGreaterThan(implementDone.get()).isGreaterThan(testDesignDone.get());
        StageContext verifyContext = verify.contexts().getFirst();
        assertThat(verifyContext.upstream()).containsKeys(IMPLEMENT, TEST_DESIGN);
        assertThat(run.stage("document").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(run.stage("security-scan").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(run.acceptedArtifact(VERIFY).orElseThrow().provenance().derivedFrom())
                .containsKeys(IMPLEMENT, TEST_DESIGN, DESIGN, INTAKE);
    }

    // ------------------------------------------------------------------ clarification

    @Test
    void ambiguityPausesForClarificationAndContinuesWithTheAnswers() {
        List<ClarificationQuestion> questions = List.of(
                new ClarificationQuestion("q1", "Which operations?", "scope", List.of("redirect")),
                new ClarificationQuestion("q2", "Which target?", "measurable", List.of("p95 < 50 ms")));
        FakeAgent intake = new FakeAgent(AgentRole.REQUIREMENTS, (ctx, call) -> ctx.hasClarificationAnswers()
                ? StageResult.success(Payloads.forRole(AgentRole.REQUIREMENTS), "clear now")
                : StageResult.needsClarification(questions));
        harness.agent(intake);
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Make it faster.", "ambiguous", options(AutonomyLevel.FULL_AUTO)).id();

        Run waiting = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                && r.stage(INTAKE).status() == StageStatus.AWAITING_CLARIFICATION);
        assertThat(waiting.clarification()).isNotNull();
        assertThat(waiting.clarification().questions()).hasSize(2);
        assertThatThrownBy(() -> engine.clarify(id, ALICE, Map.of("q1", "redirect")))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("q2");
        assertThatThrownBy(() -> engine.clarify(id, ALICE, Map.of("q1", "a", "q2", "b", "q9", "c")))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("q9");

        engine.clarify(id, ALICE, Map.of("q2", "p95 under 50 ms", "q1", "redirect"));
        Run done = completeRelease(engine, id);
        assertThat(intake.calls()).isEqualTo(2);
        assertThat(intake.contexts().get(1).clarificationAnswers())
                .containsEntry("q1", "redirect").containsEntry("q2", "p95 under 50 ms");
        assertThat(done.clarification().answeredBy()).isEqualTo("alice");
        assertThat(harness.eventTypes(id)).contains(AuditEventType.CLARIFICATION_REQUESTED,
                AuditEventType.CLARIFICATION_PROVIDED);
        assertThat(done.humanWaitIntervals()).isNotEmpty();
    }

    // ------------------------------------------------------------------ retries, fallback, rollback

    @Test
    void transientFaultIsRetriedTwiceThenSucceedsAndRecoveryIsMeasured() {
        OrchestrationEngine engine = harness.start();
        RunOptions opts = options(AutonomyLevel.FULL_AUTO)
                .withFaults(List.of(new FaultSpec(IMPLEMENT, 2, FaultKind.TRANSIENT)));
        String id = engine.start(REQUIREMENT, "fault-retry", opts).id();

        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.stage(IMPLEMENT).attempts()).isEqualTo(3);
        assertThat(atRelease.stage(IMPLEMENT).activeAgent()).isEqualTo(AgentRegistry.DETERMINISTIC);
        assertThat(atRelease.counters().retries()).isEqualTo(2);
        assertThat(atRelease.counters().fallbacks()).isZero();
        assertThat(atRelease.firstFailureAt()).isNotNull();
        assertThat(atRelease.recoveredAt()).isNotNull().isAfterOrEqualTo(atRelease.firstFailureAt());
        assertThat(harness.events(id).stream().filter(e -> e.type() == AuditEventType.RETRY_SCHEDULED))
                .hasSize(2).allSatisfy(e -> assertThat(e.data()).containsEntry("kind", "TRANSIENT"));
        Run done = completeRelease(engine, id);
        ReliabilityMetrics metrics = MetricsCalculator.compute(List.of(done), java.time.Clock.systemUTC());
        assertThat(metrics.recovery().incidents()).isEqualTo(1);
        assertThat(metrics.recovery().recovered()).isEqualTo(1);
        assertThat(metrics.stages().retries()).isEqualTo(2);
    }

    @Test
    void exhaustedPrimaryAgentFallsBackToTheDeterministicAgent() {
        OrchestrationEngine engine = harness.start();
        RunOptions opts = options(AutonomyLevel.FULL_AUTO)
                .withFaults(List.of(new FaultSpec(TEST_DESIGN, 99, FaultKind.TRANSIENT)));
        String id = engine.start(REQUIREMENT, "fault-fallback", opts).id();

        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.stage(TEST_DESIGN).status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(atRelease.stage(TEST_DESIGN).activeAgent()).isEqualTo(AgentRegistry.FALLBACK_AGENT_ID);
        assertThat(atRelease.counters().fallbacks()).isEqualTo(1);
        assertThat(atRelease.counters().retries()).isEqualTo(2);
        assertThat(atRelease.acceptedArtifact(TEST_DESIGN).orElseThrow().provenance().agentId())
                .isEqualTo(AgentRegistry.FALLBACK_AGENT_ID);
        assertThat(harness.eventTypes(id)).contains(AuditEventType.FALLBACK_ENGAGED);
        assertThat(completeRelease(engine, id).recoveredAt()).isNotNull();
    }

    @Test
    void fatalFailureWithoutFallbackRollsBackCompensatesRestoresCheckpointAndSafeStops() {
        harness.definition(d -> d.withStage(TEST_DESIGN, s -> s.withFallbackToDeterministic(false)));
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.forRole(AgentRole.IMPLEMENTATION), "implemented",
                List.of(new CompensationRequest(TEST_UNDO, "sandbox-" + call, "undo implementation " + call)))));
        harness.agent(new FakeAgent(AgentRole.TEST_DESIGN, (ctx, call) -> {
            if (call == 1) {
                await().atMost(EngineHarness.TIMEOUT).until(() -> harness.engine().get(ctx.runId())
                        .stage(IMPLEMENT).status() == StageStatus.SUCCEEDED);
                throw StageFailure.fatal("cannot design tests");
            }
            return StageResult.success(Payloads.forRole(AgentRole.TEST_DESIGN), "second time lucky");
        }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "rollback", options(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.AGENT_FAILURE_EXHAUSTED);
        assertThat(stopped.stage(TEST_DESIGN).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage(TEST_DESIGN).lastError()).contains("FATAL").contains("cannot design tests");
        assertThat(harness.compensated).singleElement().satisfies(c -> {
            assertThat(c.action()).isEqualTo(TEST_UNDO);
            assertThat(c.target()).isEqualTo("sandbox-1");
        });
        assertThat(stopped.compensations()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(CompensationStatus.EXECUTED));
        assertThat(stopped.acceptedVersions()).as("checkpoint of test-design restored")
                .containsOnlyKeys(INTAKE, "decompose", "impact", DESIGN);
        assertThat(stopped.stage(IMPLEMENT).status()).isEqualTo(StageStatus.PENDING);
        assertThat(stopped.counters().rollbacks()).isEqualTo(1);
        assertThat(harness.eventTypes(id)).contains(AuditEventType.STAGE_FAILED,
                AuditEventType.COMPENSATION_EXECUTED, AuditEventType.ROLLBACK_COMPLETED, AuditEventType.SAFE_STOP);

        engine.resume(id, ALICE);
        Run done = completeRelease(engine, id);
        assertThat(done.stage(IMPLEMENT).status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(done.acceptedVersions().get(IMPLEMENT)).isEqualTo(2);
    }

    @Test
    void fatalInjectedFaultOnStageWithoutFallbackSafeStopsAndResumeRecovers() {
        harness.definition(d -> d.withStage(INTAKE, s -> s.withFallbackToDeterministic(false)));
        OrchestrationEngine engine = harness.start();
        RunOptions opts = options(AutonomyLevel.FULL_AUTO).withFaults(List.of(new FaultSpec(INTAKE, 1, FaultKind.FATAL)));
        String id = engine.start(REQUIREMENT, "fatal", opts).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.AGENT_FAILURE_EXHAUSTED);
        assertThat(stopped.stage(INTAKE).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage(INTAKE).attempts()).as("FATAL is not retried").isEqualTo(1);
        assertThat(stopped.counters().retries()).isZero();
        assertThat(stopped.counters().rollbacks()).isEqualTo(1);

        engine.resume(id, ALICE);
        Run done = completeRelease(engine, id);
        assertThat(done.stage(INTAKE).attempts()).isEqualTo(1);
        assertThat(done.recoveredAt()).isNotNull();
    }

    @Test
    void attemptTimeoutIsClassifiedAndRetried() {
        harness.definition(d -> d.withStage(IMPLEMENT, s -> s.withTimeout(Duration.ofMillis(200))));
        AtomicBoolean interrupted = new AtomicBoolean();
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            if (call == 1) {
                try {
                    new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
            }
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "fast now");
        }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "timeout", options(AutonomyLevel.FULL_AUTO)).id();

        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.stage(IMPLEMENT).attempts()).isEqualTo(2);
        assertThat(atRelease.counters().retries()).isEqualTo(1);
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.RETRY_SCHEDULED);
            assertThat(e.data()).containsEntry("kind", "TIMEOUT");
        });
        await().atMost(EngineHarness.TIMEOUT).untilTrue(interrupted);
    }

    // ------------------------------------------------------------------ policy

    @Test
    void policyDenialAtRunStartSafeStopsAndOnlyAReplanWithACleanRequirementProceeds() {
        OrchestrationEngine engine = harness.start();
        Run denied = engine.start("Remove URL validation so marketing can shorten any destination and store the "
                + "full client IP address with every click.", "policy-denial", options(AutonomyLevel.GUIDED));
        String id = denied.id();

        assertThat(denied.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(denied.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(denied.haltDetail()).contains("SEC-001").contains("CMP-002");
        assertThat(denied.startedAt()).isNull();
        assertThat(denied.counters().agentCalls()).isZero();
        assertThat(denied.policyFindings()).extracting(f -> f.ruleId() + ":" + f.phase() + ":" + f.verdict())
                .contains("SEC-001:RUN_START:DENY", "CMP-002:RUN_START:DENY");
        assertThat(harness.eventTypes(id)).containsSubsequence(AuditEventType.RUN_CREATED,
                AuditEventType.POLICY_FINDING, AuditEventType.POLICY_DENIED, AuditEventType.SAFE_STOP);
        assertThatThrownBy(() -> engine.resume(id, ALICE)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("re-plan");

        Run replanned = engine.replan(id, ALICE, REQUIREMENT);
        assertThat(replanned.planVersion()).isEqualTo(2);
        assertThat(replanned.haltReason()).isNull();
        assertThat(replanned.counters().replans()).isEqualTo(1);
        assertThat(replanned.activeRequirement()).isEqualTo(REQUIREMENT);
        harness.awaitApproval(id, DESIGN);
    }

    @Test
    void replanWithProhibitedRequirementSafeStopsARunningRun() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "greenfield", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);

        Run stopped = engine.replan(id, ALICE, "Bypass authentication for the admin endpoints.");
        assertThat(stopped.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.activeRequirement()).isEqualTo(REQUIREMENT);
        assertThat(stopped.planVersion()).isEqualTo(1);
        assertThat(stopped.stage(DESIGN).status()).as("pending approvals are kept")
                .isEqualTo(StageStatus.AWAITING_APPROVAL);
    }

    @Test
    void exitGateDenialForLeakedSecretRollsBackAndSafeStops() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(Payloads.changeSet(
                "url-shortener/src/main/java/com/example/Feature.java", "String key = \"AKIAABCDEFGHIJKLMNOP\";"),
                "leaky")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "secret", options(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.stage(IMPLEMENT).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage(IMPLEMENT).lastError()).contains("SEC-002");
        assertThat(stopped.acceptedVersions()).doesNotContainKey(IMPLEMENT);
        assertThat(stopped.artifacts().stream().filter(a -> a.stageId().equals(IMPLEMENT))).hasSize(1);
        assertThat(stopped.haltDetail()).doesNotContain("AKIAABCDEFGHIJKLMNOP");
    }

    @Test
    void protectedPathRequiresApprovalEvenInFullAuto() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(Payloads.changeSet(
                "url-shortener/src/main/java/com/example/security/Feature.java", "class Feature {}"), "security")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "protected", options(AutonomyLevel.FULL_AUTO)).id();

        Run waiting = harness.awaitApproval(id, IMPLEMENT);
        assertThat(waiting.stage(IMPLEMENT).pendingApproval().ruleIds()).containsExactly("SEC-003");
        engine.approve(id, IMPLEMENT, ALICE, "reviewed the security change");
        completeRelease(engine, id);
    }

    @Test
    void noGoReleaseRequiresAWaiverCommentToApprove() {
        harness.agent(new FakeAgent(AgentRole.RELEASE, (ctx, call) -> StageResult.success(
                Payloads.release(ReleaseReadiness.GoNoGo.NO_GO), "no go")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "no-go", options(AutonomyLevel.FULL_AUTO)).id();

        Run waiting = harness.awaitApproval(id, RELEASE);
        assertThat(waiting.stage(RELEASE).pendingApproval().ruleIds()).contains("CHG-003");
        assertThatThrownBy(() -> engine.approve(id, RELEASE, ALICE, " ")).isInstanceOf(InvalidCommandException.class)
                .hasMessageContaining("waiver");
        engine.approve(id, RELEASE, ALICE, "waiver: accepted by the release board");
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);
        ReleaseReadiness release = done.acceptedArtifact(RELEASE).orElseThrow().payloadAs(ReleaseReadiness.class);
        assertThat(release.changeRecord().policyVerdict()).contains("waiver");
        assertThat(release.changeRecord().approvals()).singleElement()
                .satisfies(a -> assertThat(a.comment()).contains("waiver"));
    }

    // ------------------------------------------------------------------ feedback loop

    @Test
    void failedVerificationFeedsBackToImplementationAndTheSecondVerificationPasses() {
        FakeAgent implement = FakeAgent.succeeding(AgentRole.IMPLEMENTATION);
        FakeAgent verify = new FakeAgent(AgentRole.VERIFICATION, (ctx, call) ->
                StageResult.success(Payloads.verification(call > 1), "verify " + call));
        harness.agent(implement).agent(verify);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "feedback", options(AutonomyLevel.FULL_AUTO)).id();

        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.counters().feedbackLoops()).isEqualTo(1);
        assertThat(implement.calls()).isEqualTo(2);
        assertThat(implement.contexts().get(1).feedback()).singleElement().asString()
                .contains("[verify@v1]").contains("Add tests for: Feature");
        assertThat(verify.calls()).isEqualTo(2);
        assertThat(atRelease.artifacts().stream().filter(a -> a.stageId().equals(VERIFY)).map(Artifact::version))
                .containsExactly(1, 2);
        assertThat(atRelease.acceptedVersions()).containsEntry(VERIFY, 2).containsEntry(IMPLEMENT, 2);
        assertThat(harness.events(id).stream().filter(e -> e.type() == AuditEventType.FEEDBACK_LOOP)).hasSize(1);
        completeRelease(engine, id);
    }

    @Test
    void verificationThatKeepsFailingExhaustsTheFeedbackBudgetAndSafeStops() {
        FakeAgent implement = FakeAgent.succeeding(AgentRole.IMPLEMENTATION);
        harness.agent(implement).agent(new FakeAgent(AgentRole.VERIFICATION, (ctx, call) ->
                StageResult.success(Payloads.verification(false), "still failing")));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "feedback-budget", options(AutonomyLevel.FULL_AUTO)).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.AGENT_FAILURE_EXHAUSTED);
        assertThat(stopped.stage(VERIFY).status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage(VERIFY).lastError()).isEqualTo("verification kept failing after 2 feedback loops");
        assertThat(stopped.counters().feedbackLoops()).isEqualTo(3);
        assertThat(implement.calls()).isEqualTo(3);
        assertThat(stopped.counters().rollbacks()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ re-planning

    @Test
    void replanAfterDesignApprovalInvalidatesDownstreamRevokesApprovalAndRequiresReapproval() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeAgent implement = new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            if (call == 1) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    cancelled.set(true);
                    throw e;
                }
            }
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "implemented");
        });
        FakeAgent intake = FakeAgent.succeeding(AgentRole.REQUIREMENTS);
        harness.agent(implement).agent(intake);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "replan", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);
        engine.approve(id, DESIGN, ALICE, "approved v1");
        harness.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);

        Run replanned = engine.replan(id, ALICE, REQUIREMENT + " Support custom aliases.");
        release.countDown();
        assertThat(replanned.planVersion()).isEqualTo(2);
        assertThat(replanned.counters().replans()).isEqualTo(1);
        assertThat(replanned.stage(DESIGN).status()).isEqualTo(StageStatus.STALE);
        assertThat(replanned.stage("decompose").status()).isEqualTo(StageStatus.STALE);
        assertThat(replanned.stage(IMPLEMENT).status()).isEqualTo(StageStatus.PENDING);
        assertThat(replanned.stage(INTAKE).status()).isIn(StageStatus.RUNNING, StageStatus.SUCCEEDED);
        assertThat(replanned.approvals()).singleElement().satisfies(a -> {
            assertThat(a.stageId()).isEqualTo(DESIGN);
            assertThat(a.status()).isEqualTo(ApprovalStatus.REVOKED);
            assertThat(a.decidedBy()).isEqualTo("alice");
        });
        assertThat(replanned.counters().approvalsRevoked()).isEqualTo(1);
        assertThat(replanned.acceptedVersions()).containsKeys(INTAKE, DESIGN);
        assertThat(harness.eventTypes(id)).contains(AuditEventType.REPLAN, AuditEventType.APPROVAL_REVOKED,
                AuditEventType.STAGE_INVALIDATED);
        await().atMost(EngineHarness.TIMEOUT).untilTrue(cancelled);

        Run again = harness.awaitApproval(id, DESIGN);
        Approval pending = again.stage(DESIGN).pendingApproval();
        assertThat(pending.planVersion()).isEqualTo(2);
        assertThat(intake.contexts().getLast().requirement()).endsWith("Support custom aliases.");
        assertThat(again.acceptedArtifact(DESIGN).orElseThrow().version()).isEqualTo(2);
        engine.approve(id, DESIGN, ALICE, "approved v2");
        Run done = completeRelease(engine, id);
        ReleaseReadiness record = done.acceptedArtifact(RELEASE).orElseThrow().payloadAs(ReleaseReadiness.class);
        assertThat(record.changeRecord().approvals()).extracting(ReleaseReadiness.ApprovalRecord::comment)
                .containsExactly("approved v2", "ship it");
    }

    @Test
    void replanBudgetIsEnforced() {
        OrchestrationEngine engine = harness.start();
        RunOptions opts = options(AutonomyLevel.GUIDED).withBudget(RunBudget.defaults().withMaxReplans(1));
        String id = engine.start(REQUIREMENT, "replan-budget", opts).id();
        harness.awaitApproval(id, DESIGN);
        engine.replan(id, ALICE, REQUIREMENT + " v2");
        harness.awaitApproval(id, DESIGN);
        assertThatThrownBy(() -> engine.replan(id, ALICE, REQUIREMENT + " v3"))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("re-plan budget");
    }

    // ------------------------------------------------------------------ budgets, stop/resume, recovery

    @Test
    void agentCallBudgetExceededSafeStopsTheRun() {
        OrchestrationEngine engine = harness.start();
        RunOptions opts = options(AutonomyLevel.FULL_AUTO).withBudget(RunBudget.defaults().withMaxAgentCalls(3));
        String id = engine.start(REQUIREMENT, "budget", opts).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.BUDGET_EXCEEDED);
        assertThat(stopped.haltDetail()).contains("agent call budget of 3");
        assertThat(stopped.counters().agentCalls()).isEqualTo(3);
        assertThat(stopped.stage(DESIGN).status()).isEqualTo(StageStatus.PENDING);
        assertThat(harness.eventTypes(id)).contains(AuditEventType.BUDGET_EXCEEDED);

        Run resumed = engine.resume(id, ALICE);
        assertThat(resumed.status()).as("the budget is still exhausted").isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(resumed.haltReason()).isEqualTo(HaltReason.BUDGET_EXCEEDED);
    }

    @Test
    void stopCancelsInFlightWorkAndResumeContinues() {
        CountDownLatch block = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        FakeAgent implement = new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            if (call == 1) {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
            }
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "implemented");
        });
        harness.agent(implement);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "stop", options(AutonomyLevel.FULL_AUTO)).id();
        harness.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);

        Run stopped = engine.stop(id, ALICE, "maintenance window");
        assertThat(stopped.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.OPERATOR_STOP);
        assertThat(stopped.haltDetail()).isEqualTo("maintenance window");
        assertThat(stopped.stage(IMPLEMENT).status()).isEqualTo(StageStatus.PENDING);
        await().atMost(EngineHarness.TIMEOUT).untilTrue(interrupted);
        assertThatThrownBy(() -> engine.stop(id, ALICE, "again")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.approve(id, RELEASE, ALICE, null)).isInstanceOf(IllegalStateException.class);
        assertThat(harness.events(id).stream().filter(e -> e.type() == AuditEventType.SAFE_STOP))
                .singleElement().satisfies(e -> assertThat(e.actor()).isEqualTo(ALICE));

        Run resumed = engine.resume(id, ALICE);
        assertThat(resumed.status()).isEqualTo(RunStatus.RUNNING);
        Run done = completeRelease(engine, id);
        assertThat(implement.calls()).isEqualTo(2);
        assertThat(done.humanWaitIntervals()).anySatisfy(i -> assertThat(i.reason()).isEqualTo("SAFE_STOPPED"));
        assertThat(harness.eventTypes(id)).contains(AuditEventType.RUN_RESUMED);
    }

    @Test
    void recoverInterruptedSafeStopsRunsThatWereInProgressAndResumeContinues() {
        CountDownLatch never = new CountDownLatch(1);
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            never.await(10, TimeUnit.SECONDS);
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "late");
        }));
        OrchestrationEngine first = harness.start();
        String id = first.start(REQUIREMENT, "restart", options(AutonomyLevel.FULL_AUTO)).id();
        harness.awaitStage(id, IMPLEMENT, StageStatus.RUNNING);
        first.close();
        assertThat(harness.store.load(id).orElseThrow().stage(IMPLEMENT).status()).isEqualTo(StageStatus.RUNNING);

        EngineHarness restarted = new EngineHarness(harness.store, harness.audit);
        try {
            OrchestrationEngine second = restarted.start();
            second.recoverInterrupted();
            Run recovered = second.get(id);
            assertThat(recovered.status()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(recovered.haltReason()).isEqualTo(HaltReason.INTERRUPTED_BY_RESTART);
            assertThat(recovered.stage(IMPLEMENT).status()).isEqualTo(StageStatus.PENDING);
            assertThat(restarted.eventTypes(id)).contains(AuditEventType.RUN_INTERRUPTED);
            assertThat(second.list()).extracting(Run::id).contains(id);

            second.resume(id, ALICE);
            restarted.awaitApproval(id, RELEASE);
            second.approve(id, RELEASE, ALICE, null);
            assertThat(restarted.awaitStatus(id, RunStatus.COMPLETED).stage(IMPLEMENT).status())
                    .isEqualTo(StageStatus.SUCCEEDED);
        } finally {
            never.countDown();
            restarted.close();
        }
    }

    // ------------------------------------------------------------------ human decisions

    @Test
    void rejectWithReworkReRunsTheStageAndAbortEndsTheRun() {
        FakeAgent design = FakeAgent.succeeding(AgentRole.ARCHITECTURE);
        harness.agent(design);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "rework", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);

        engine.reject(id, DESIGN, ALICE, "add an alternative with a cache", RejectDisposition.REWORK);
        Run again = harness.awaitRun(id, r -> r.status() == RunStatus.WAITING_HUMAN
                && r.stage(DESIGN).status() == StageStatus.AWAITING_APPROVAL && r.approvals().size() == 2);
        assertThat(design.calls()).isEqualTo(2);
        assertThat(design.contexts().get(1).feedback()).singleElement().asString()
                .contains("add an alternative with a cache");
        assertThat(again.counters().feedbackLoops()).isEqualTo(1);
        assertThat(again.counters().approvalsRejected()).isEqualTo(1);
        assertThat(again.approvals().getFirst().status()).isEqualTo(ApprovalStatus.REJECTED);

        Run aborted = engine.reject(id, DESIGN, ALICE, "out of scope", RejectDisposition.ABORT);
        assertThat(aborted.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(aborted.endedAt()).isNotNull();
        assertThat(aborted.stage(DESIGN).status()).isEqualTo(StageStatus.CANCELLED);
        assertThat(aborted.stage(IMPLEMENT).status()).isEqualTo(StageStatus.CANCELLED);
        assertThat(aborted.stage(INTAKE).status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(harness.eventTypes(id)).contains(AuditEventType.RUN_ABORTED);
        assertThatThrownBy(() -> engine.replan(id, ALICE, "anything")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commandsValidateActorsInputAndState() {
        OrchestrationEngine engine = harness.start();
        assertThatThrownBy(() -> engine.start(" ", "x", RunOptions.defaults()))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> engine.start(REQUIREMENT, "x", RunOptions.defaults().withProvider("nope")))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> engine.start(REQUIREMENT, "x", RunOptions.defaults()
                .withFaults(List.of(new FaultSpec("unknown-stage", 1, FaultKind.FATAL)))))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> engine.get("missing")).isInstanceOf(RunNotFoundException.class);

        String id = engine.start(REQUIREMENT, "", options(AutonomyLevel.GUIDED)).id();
        assertThat(engine.get(id).scenarioKey()).isEqualTo("custom");
        harness.awaitApproval(id, DESIGN);
        assertThatThrownBy(() -> engine.approve(id, DESIGN, Actor.agent("deterministic"), null))
                .isInstanceOf(InvalidCommandException.class).hasMessageContaining("human");
        assertThatThrownBy(() -> engine.approve(id, IMPLEMENT, ALICE, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.approve(id, "nope", ALICE, null)).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> engine.reject(id, DESIGN, ALICE, " ", RejectDisposition.REWORK))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> engine.clarify(id, ALICE, Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.resume(id, ALICE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getAndListReturnIsolatedCopies() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "copies", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);
        Run copy = engine.get(id);
        copy.setStatus(RunStatus.ABORTED);
        copy.stage(DESIGN).setStatus(StageStatus.FAILED);
        assertThat(engine.get(id).status()).isEqualTo(RunStatus.WAITING_HUMAN);
        assertThat(engine.get(id).stage(DESIGN).status()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        String second = engine.start(REQUIREMENT, "copies-2", options(AutonomyLevel.GUIDED)).id();
        List<Run> runs = engine.list();
        assertThat(runs).extracting(Run::id).containsExactlyInAnyOrder(second, id);
        assertThat(runs.getFirst().createdAt()).isAfterOrEqualTo(runs.getLast().createdAt());
        runs.getFirst().setStatus(RunStatus.ABORTED);
        assertThat(engine.get(runs.getFirst().id()).status()).isNotEqualTo(RunStatus.ABORTED);
    }

    @Test
    void humanWaitTimeIsExcludedFromActiveTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        harness.config(b -> b.clock(clock));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "timing", options(AutonomyLevel.GUIDED)).id();
        harness.awaitApproval(id, DESIGN);
        clock.advance(Duration.ofMinutes(5));
        engine.approve(id, DESIGN, ALICE, null);
        harness.awaitApproval(id, RELEASE);
        clock.advance(Duration.ofMinutes(2));
        engine.approve(id, RELEASE, ALICE, null);
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        assertThat(done.humanWaitIntervals()).extracting(i -> i.durationMs(done.endedAt()))
                .containsExactly(300_000L, 120_000L);
        assertThat(done.endToEndMs()).isEqualTo(420_000L);
        assertThat(done.activeMs(done.endedAt())).isZero();
        assertThat(done.stage(DESIGN).pendingApproval()).isNull();
        List<Approval> decided = new ArrayList<>(done.approvals());
        assertThat(decided).extracting(a -> java.time.Duration.between(a.requestedAt(), a.decidedAt()).toMinutes())
                .containsExactly(5L, 2L);
        ReliabilityMetrics metrics = MetricsCalculator.compute(List.of(done), clock);
        assertThat(metrics.latency().humanWaitMsAvg()).isEqualTo(420_000.0);
        assertThat(metrics.latency().activeMsAvg()).isZero();
        assertThat(metrics.approvals().meanDecisionMs()).isEqualTo(210_000.0);
    }

    @Test
    void engineListenerReceivesCopiesAfterEveryMutation() {
        List<RunStatus> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        harness.config(b -> b.listener(run -> statuses.add(run.status())));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "listener", options(AutonomyLevel.FULL_AUTO)).id();
        completeRelease(engine, id);
        assertThat(statuses).contains(RunStatus.RUNNING, RunStatus.WAITING_HUMAN, RunStatus.COMPLETED);
    }

    @Test
    void closedEngineRejectsCommands() {
        OrchestrationEngine engine = harness.start();
        engine.close();
        assertThatThrownBy(() -> engine.start(REQUIREMENT, "closed", RunOptions.defaults()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void artifactKindsMatchTheirStages() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "kinds", options(AutonomyLevel.FULL_AUTO)).id();
        Run done = completeRelease(engine, id);
        assertThat(done.acceptedArtifact(INTAKE).orElseThrow().kind()).isEqualTo(ArtifactKind.REQUIREMENT_SPEC);
        assertThat(done.acceptedArtifact(VERIFY).orElseThrow().kind()).isEqualTo(ArtifactKind.VERIFICATION_REPORT);
        assertThat(done.acceptedArtifacts()).allSatisfy(a -> assertThat(a.hash()).hasSize(64));
        assertThat(done.currentRisk().level()).isEqualTo(RiskLevel.MEDIUM);
    }
}
