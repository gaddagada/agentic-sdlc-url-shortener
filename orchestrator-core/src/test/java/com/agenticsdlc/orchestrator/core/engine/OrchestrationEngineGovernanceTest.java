package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.TEST_UNDO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.LlmTrace;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalKind;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
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
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.MutableClock;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

import tools.jackson.core.JacksonException;

/**
 * Governance edge cases of the engine: failing compensations, entry-gate denials, invalid outputs, token and
 * time budgets, stop during a scheduled retry, abort compensation, entry rework, lazy loading and metrics.
 */
class OrchestrationEngineGovernanceTest {

    private static final String REQUIREMENT = "Create short links for HTTP URLs and redirect with HTTP 302.";
    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    private static RunOptions fullAuto() {
        return RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO);
    }

    @Test
    void failingCompensationTurnsTheHaltIntoRollbackFailed() {
        harness.definition(d -> d.withStage("test-design", s -> s.withFallbackToDeterministic(false)));
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.forRole(AgentRole.IMPLEMENTATION), "impl",
                List.of(new CompensationRequest("NO_SUCH_ACTION", "x", "cannot be undone")))));
        harness.agent(new FakeAgent(AgentRole.TEST_DESIGN, (ctx, call) -> {
            await().atMost(EngineHarness.TIMEOUT).until(() -> harness.engine().get(ctx.runId())
                    .stage("implement").status() == StageStatus.SUCCEEDED);
            throw StageFailure.fatal("boom");
        }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "rollback-failed", fullAuto()).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.ROLLBACK_FAILED);
        assertThat(stopped.haltDetail()).contains("AGENT_FAILURE_EXHAUSTED").contains("boom");
        assertThat(stopped.compensations()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CompensationStatus.FAILED);
            assertThat(c.error()).contains("no compensation handler");
        });
        assertThat(harness.eventTypes(id)).contains(AuditEventType.COMPENSATION_FAILED,
                AuditEventType.ROLLBACK_COMPLETED);
    }

    @Test
    void entryGateDenialFailsTheStageAndSafeStopsWithPolicyDenied() {
        PolicyRule noDesignOnFriday = new PolicyRule() {
            @Override
            public String id() {
                return "TEST-001";
            }

            @Override
            public Optional<PolicyFinding> evaluate(com.agenticsdlc.orchestrator.core.policy.PolicyContext ctx) {
                return ctx.phase() == GatePhase.ENTRY && "design".equals(ctx.stageId())
                        ? Optional.of(new PolicyFinding("TEST-001", Severity.HIGH, Verdict.DENY, "change freeze"))
                        : Optional.empty();
            }
        };
        List<PolicyRule> rules = new ArrayList<>(DefaultPolicyRules.all());
        rules.add(noDesignOnFriday);
        harness.policy(new PolicyEngine(rules));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "freeze", fullAuto()).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(stopped.stage("design").status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage("design").lastError()).contains("TEST-001").contains("change freeze");
        assertThat(stopped.policyFindings()).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("TEST-001");
            assertThat(f.phase()).isEqualTo("ENTRY");
        });
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.POLICY_DENIED);
            assertThat(e.data()).containsEntry("phase", "ENTRY");
        });
    }

    @Test
    void invalidOutputIsRetriedWithTheValidationErrorAsContext() {
        FakeAgent design = new FakeAgent(AgentRole.ARCHITECTURE, (ctx, call) -> call == 1
                ? StageResult.success(new DesignSpec("", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), null, List.of(), null, List.of()), "sloppy")
                : StageResult.success(Payloads.design(RiskLevel.LOW), "fixed"));
        harness.agent(design);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "invalid", fullAuto()).id();

        Run atRelease = harness.awaitApproval(id, "release-readiness");
        assertThat(atRelease.stage("design").attempts()).isEqualTo(2);
        assertThat(design.contexts().get(1).previousError()).startsWith("INVALID_OUTPUT")
                .contains("overview must not be blank");
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.RETRY_SCHEDULED);
            assertThat(e.data()).containsEntry("kind", "INVALID_OUTPUT");
        });
        assertThat(atRelease.artifacts().stream().filter(a -> a.stageId().equals("design"))).hasSize(1);
    }

    @Test
    void wrongPayloadKindIsInvalidOutputAndExhaustsAttemptsAndFallback() {
        FakeAgent decompose = new FakeAgent(AgentRole.PLANNING, (ctx, call) ->
                StageResult.success(Payloads.design(RiskLevel.LOW), "wrong kind"));
        harness.agent(decompose);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "wrong-kind", fullAuto()).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.stage("decompose").status()).isEqualTo(StageStatus.FAILED);
        assertThat(stopped.stage("decompose").lastError()).contains("must produce TASK_PLAN");
        assertThat(stopped.counters().fallbacks()).isEqualTo(1);
        assertThat(decompose.calls()).isEqualTo(6);
        assertThat(stopped.counters().retries()).isEqualTo(4);
    }

    @Test
    void unexpectedExceptionsAreFatalAndEngageTheFallback() {
        FakeAgent impact = new FakeAgent(AgentRole.CODEBASE_ANALYSIS, (ctx, call) -> {
            if (call == 1) {
                throw new IllegalStateException("bug in the primary agent");
            }
            return StageResult.success(Payloads.impact(), "fallback ok");
        });
        harness.agent(impact);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "bug", fullAuto()).id();

        Run atRelease = harness.awaitApproval(id, "release-readiness");
        assertThat(atRelease.stage("impact").activeAgent()).isEqualTo(AgentRegistry.FALLBACK_AGENT_ID);
        assertThat(atRelease.counters().retries()).as("FATAL is not retried").isZero();
        assertThat(atRelease.counters().fallbacks()).isEqualTo(1);
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.FALLBACK_ENGAGED);
            assertThat(e.data()).containsEntry("kind", "FATAL");
        });
    }

    @Test
    void llmUsageIsRecordedInProvenanceAndCountersAndBoundedByTheTokenBudget() {
        harness.agent(new FakeAgent(AgentRole.REQUIREMENTS, (ctx, call) -> new StageResult.Success(
                Payloads.forRole(AgentRole.REQUIREMENTS), "llm", List.of(),
                new LlmTrace("anthropic", "claude-opus-5-5", "abc123", 150, 60))));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "tokens", fullAuto()
                .withBudget(RunBudget.defaults().withMaxLlmTokens(200))).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.BUDGET_EXCEEDED);
        assertThat(stopped.haltDetail()).contains("LLM token budget of 200");
        assertThat(stopped.counters().llmCalls()).isEqualTo(1);
        assertThat(stopped.counters().llmInputTokens()).isEqualTo(150);
        assertThat(stopped.counters().llmOutputTokens()).isEqualTo(60);
        Artifact intake = stopped.acceptedArtifact("intake").orElseThrow();
        assertThat(intake.provenance().providerId()).isEqualTo("anthropic");
        assertThat(intake.provenance().model()).isEqualTo("claude-opus-5-5");
        assertThat(intake.provenance().promptHash()).isEqualTo("abc123");
        assertThat(intake.provenance().inputTokens()).isEqualTo(150);
    }

    @Test
    void activeDurationBudgetExcludesNothingButHumanWaitAndStopsLongRuns() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        harness.config(b -> b.clock(clock));
        harness.agent(new FakeAgent(AgentRole.PLANNING, (ctx, call) -> {
            clock.advance(Duration.ofHours(3));
            return StageResult.success(Payloads.taskPlan(), "slow");
        }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "slow", fullAuto()).id();

        Run stopped = harness.awaitStatus(id, RunStatus.SAFE_STOPPED);
        assertThat(stopped.haltReason()).isEqualTo(HaltReason.BUDGET_EXCEEDED);
        assertThat(stopped.haltDetail()).contains("active duration budget of PT2H");
    }

    @Test
    void stopDuringAScheduledRetryKeepsItAndResumeRearmsIt() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        try {
            scheduler.submit(() -> gate.await(10, TimeUnit.SECONDS));
            harness.config(b -> b.retryScheduler(scheduler));
            OrchestrationEngine engine = harness.start();
            String id = engine.start(REQUIREMENT, "retry-stop", fullAuto()
                    .withFaults(List.of(new FaultSpec("implement", 1, FaultKind.TRANSIENT)))).id();
            harness.awaitStage(id, "implement", StageStatus.RETRY_SCHEDULED);

            Run stopped = engine.stop(id, ALICE, "pause");
            assertThat(stopped.stage("implement").status()).isEqualTo(StageStatus.RETRY_SCHEDULED);
            gate.countDown();
            scheduler.submit(() -> null).get(10, TimeUnit.SECONDS);
            Run stillStopped = engine.get(id);
            assertThat(stillStopped.status()).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThat(stillStopped.stage("implement").status()).isEqualTo(StageStatus.RETRY_SCHEDULED);
            assertThat(stillStopped.stage("implement").attempts()).isEqualTo(1);

            engine.resume(id, ALICE);
            Run atRelease = harness.awaitApproval(id, "release-readiness");
            assertThat(atRelease.stage("implement").attempts()).isEqualTo(2);
        } finally {
            gate.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void abortExecutesRegisteredCompensations() {
        harness.agent(new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> StageResult.success(
                Payloads.forRole(AgentRole.IMPLEMENTATION), "impl",
                List.of(new CompensationRequest(TEST_UNDO, "sandbox-abort", "undo")))));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "abort", fullAuto()).id();
        harness.awaitApproval(id, "release-readiness");

        Run aborted = engine.reject(id, "release-readiness", ALICE, "not this quarter", RejectDisposition.ABORT);
        assertThat(aborted.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(aborted.stage("release-readiness").status()).isEqualTo(StageStatus.CANCELLED);
        assertThat(aborted.stage("implement").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(harness.compensated).extracting(c -> c.target()).containsExactly("sandbox-abort");
        assertThat(aborted.compensations()).allSatisfy(c -> assertThat(c.status())
                .isEqualTo(CompensationStatus.EXECUTED));
        assertThat(aborted.humanWaitIntervals()).allSatisfy(i -> assertThat(i.open()).isFalse());
    }

    @Test
    void reworkAtAnEntryGateReRequestsTheEntryApprovalAndRunsTheStageWithTheFeedback() {
        FakeAgent decompose = FakeAgent.succeeding(AgentRole.PLANNING);
        harness.agent(decompose);
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "strict", RunOptions.defaults().withAutonomy(AutonomyLevel.STRICT)).id();
        String first = harness.awaitApproval(id, "decompose").stage("decompose").pendingApproval().id();

        Run reworked = engine.reject(id, "decompose", ALICE, "split the work by endpoint", RejectDisposition.REWORK);
        assertThat(decompose.calls()).as("an entry rework must not start the stage").isZero();
        assertThat(reworked.stage("decompose").status()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        Approval second = reworked.stage("decompose").pendingApproval();
        assertThat(second.id()).isNotEqualTo(first);
        assertThat(second.kind()).isEqualTo(ApprovalKind.ENTRY);
        assertThat(second.ruleIds()).contains("GOV-002");
        assertThat(reworked.stage("decompose").feedback()).singleElement().asString()
                .contains("split the work by endpoint");

        engine.approve(id, "decompose", ALICE, "ok with the split", second.id());
        Run run = harness.awaitStage(id, "decompose", StageStatus.SUCCEEDED);
        assertThat(decompose.calls()).isEqualTo(1);
        assertThat(decompose.contexts().getFirst().feedback()).singleElement().asString()
                .contains("split the work by endpoint");
        assertThat(run.approvals()).filteredOn(a -> a.stageId().equals("decompose"))
                .extracting(a -> a.kind() + ":" + a.status())
                .containsExactly("ENTRY:REJECTED", "ENTRY:APPROVED");
        assertThat(run.counters().feedbackLoops()).isEqualTo(1);
    }

    @Test
    void runsAreLoadedLazilyFromTheStoreByANewEngine() {
        OrchestrationEngine first = harness.start();
        String id = first.start(REQUIREMENT, "lazy", RunOptions.defaults()).id();
        harness.awaitApproval(id, "design");
        first.close();

        EngineHarness second = new EngineHarness(harness.store, harness.audit);
        try {
            OrchestrationEngine engine = second.start();
            assertThat(engine.list()).as("list() includes persisted runs").extracting(Run::id).containsExactly(id);
            assertThat(engine.get(id).stage("design").status()).isEqualTo(StageStatus.AWAITING_APPROVAL);
            assertThat(engine.isCached(id)).as("reads do not load runs into memory").isFalse();
            engine.approve(id, "design", ALICE, null);
            assertThat(engine.isCached(id)).as("a command rehydrates the run").isTrue();
            second.awaitApproval(id, "release-readiness");
            assertThat(engine.list()).extracting(Run::id).containsExactly(id);
        } finally {
            second.close();
        }
    }

    @Test
    void metricsAreRecordedAndRecorderFailuresAreIsolated() {
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        harness.metrics(new MetricsRecorder() {
            @Override
            public void stageCompleted(String stageId, StageStatus status, long durationMs) {
                calls.add("stage:" + stageId + ":" + status);
                failures.incrementAndGet();
                throw new IllegalStateException("metrics backend down");
            }

            @Override
            public void retry(String stageId) {
                calls.add("retry:" + stageId);
            }

            @Override
            public void fallback(String stageId) {
                calls.add("fallback:" + stageId);
            }

            @Override
            public void rollback(String runId) {
                calls.add("rollback");
            }

            @Override
            public void runCompleted(long durationMs, long activeMs) {
                calls.add("completed");
            }

            @Override
            public void agentCall(String providerId, AgentRole role, long latencyMs, long in, long out) {
                calls.add("agent:" + providerId + ":" + role);
            }

            @Override
            public void approvalDecision(long decisionMs, boolean approved) {
                calls.add("approval:" + approved);
            }
        });
        harness.config(b -> b.listener(run -> {
            throw new IllegalStateException("listener down");
        }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "metrics", fullAuto()
                .withFaults(List.of(new FaultSpec("implement", 1, FaultKind.TRANSIENT)))).id();
        harness.awaitApproval(id, "release-readiness");
        engine.approve(id, "release-readiness", ALICE, null);
        harness.awaitStatus(id, RunStatus.COMPLETED);

        assertThat(calls).contains("retry:implement", "completed", "approval:true",
                "agent:deterministic:IMPLEMENTATION", "stage:implement:RETRY_SCHEDULED", "stage:implement:SUCCEEDED");
        assertThat(failures.get()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void exceptionsAreClassifiedIntoFailureKinds() {
        assertThat(StageExecutor.classify(StageFailure.timeout("t")).kind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(StageExecutor.classify(new InvalidArtifactException("X", List.of("bad"))).kind())
                .isEqualTo(FailureKind.INVALID_OUTPUT);
        assertThat(StageExecutor.classify(new TimeoutException("slow")).kind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(StageExecutor.classify(new IOException("net")).kind()).isEqualTo(FailureKind.TRANSIENT);
        assertThat(StageExecutor.classify(new java.io.UncheckedIOException(new IOException("net"))).kind())
                .isEqualTo(FailureKind.TRANSIENT);
        assertThat(StageExecutor.classify(new InterruptedException()).kind()).isEqualTo(FailureKind.TRANSIENT);
        assertThat(StageExecutor.classify(new NullPointerException("npe")).kind()).isEqualTo(FailureKind.FATAL);
        JacksonException parse = org.assertj.core.api.Assertions.catchThrowableOfType(JacksonException.class,
                () -> com.agenticsdlc.orchestrator.core.json.JsonCodec.standard().fromJson("{", DesignSpec.class));
        assertThat(StageExecutor.classify(parse).kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
    }
}
