package com.agenticsdlc.orchestrator.core.evaluation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuite.EvaluationCase;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuite.Expectations;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.policy.rules.NoGoReleaseRule;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Executes an {@link EvaluationSuite} through the engine with whatever agents the engine factory configures, and
 * checks every case's expectations.
 *
 * <p>Per case the runner creates an engine ({@link EngineFactory#create}), starts the run as its operator identity,
 * and polls it. Unless the case expects the run not to complete ({@code expectCompleted: false}), it drives the run:
 * it answers clarification questions with the first suggested answer of each question and approves every pending
 * gate. Approvals are governance actions that the engine accepts only from a human actor; the runner therefore
 * approves as a dedicated, clearly named operator identity ({@value #DEFAULT_APPROVER} by default) with the comment
 * {@value #APPROVAL_COMMENT}, so every automated approval is attributable in the audit trail. The runner never waives
 * a NO_GO release ({@code CHG-003}): it stops there (the release decision can still be checked, and a completion
 * expectation then fails). When the case expects the run not to complete, the runner stops at the first human gate.
 * Each case is bounded by a timeout, and the engine is closed afterwards. Then the run is evaluated with
 * {@link RunEvaluator} and the expectations are checked.</p>
 *
 * <p>Use it offline (CI, a dedicated evaluation deployment): auto-approval defeats the purpose of human gates in a
 * production engine.</p>
 */
public final class EvaluationSuiteRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationSuiteRunner.class);

    /** Default operator identity of the runner's approvals and clarification answers. */
    public static final String DEFAULT_APPROVER = "evaluation-runner";

    /** Comment of every approval the runner gives. */
    public static final String APPROVAL_COMMENT = "auto-approved by the evaluation runner";

    /** Default timeout of one case. */
    public static final Duration DEFAULT_CASE_TIMEOUT = Duration.ofSeconds(30);

    /** Maximum governance actions (approvals and clarifications) per case. */
    static final int MAX_ACTIONS = 50;

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    /**
     * Creates the engine for a case.
     */
    @FunctionalInterface
    public interface EngineFactory {

        /**
         * Creates a fresh engine for a case; the runner closes it when the case is done.
         *
         * @param evaluationCase the case
         * @return the engine
         */
        OrchestrationEngine create(EvaluationCase evaluationCase);

        /**
         * Returns the codebase index the engine of a case reasons over, for the codebase criteria of the evaluation.
         *
         * @param evaluationCase the case
         * @return the index, or null (default)
         */
        default @Nullable CodebaseIndex index(EvaluationCase evaluationCase) {
            return null;
        }
    }

    private final Actor operator;
    private final Duration caseTimeout;

    /**
     * Creates a runner with the default operator identity and case timeout.
     */
    public EvaluationSuiteRunner() {
        this(DEFAULT_APPROVER, DEFAULT_CASE_TIMEOUT);
    }

    /**
     * Creates a runner.
     *
     * @param operatorId operator identity of the runner's approvals and answers
     * @param caseTimeout timeout of one case
     */
    public EvaluationSuiteRunner(String operatorId, Duration caseTimeout) {
        this.operator = Actor.human(operatorId);
        this.caseTimeout = Objects.requireNonNull(caseTimeout, "caseTimeout");
        if (caseTimeout.isNegative() || caseTimeout.isZero()) {
            throw new IllegalArgumentException("caseTimeout must be positive");
        }
    }

    /**
     * Runs a suite with the default runner.
     *
     * @param engineFactory creates an engine per case
     * @param suite the suite
     * @return the result
     */
    public static SuiteResult run(EngineFactory engineFactory, EvaluationSuite suite) {
        return new EvaluationSuiteRunner().runSuite(engineFactory, suite);
    }

    /**
     * Runs a suite.
     *
     * @param engineFactory creates an engine per case
     * @param suite the suite
     * @return the result
     */
    public SuiteResult runSuite(EngineFactory engineFactory, EvaluationSuite suite) {
        Objects.requireNonNull(engineFactory, "engineFactory");
        List<SuiteResult.CaseResult> results = new ArrayList<>();
        for (EvaluationCase c : suite.cases()) {
            SuiteResult.CaseResult result = runCase(engineFactory, c);
            LOG.info("evaluation case finished case={} passed={} status={} failures={}", c.id(), result.passed(),
                    result.status(), result.failures());
            results.add(result);
        }
        return new SuiteResult(suite.name(), suite.version(), results);
    }

    /**
     * Runs one case.
     *
     * @param engineFactory creates the engine
     * @param c the case
     * @return the result
     */
    public SuiteResult.CaseResult runCase(EngineFactory engineFactory, EvaluationCase c) {
        List<String> observations = new ArrayList<>();
        OrchestrationEngine engine;
        try {
            engine = engineFactory.create(c);
        } catch (RuntimeException e) {
            return new SuiteResult.CaseResult(c.id(), false, List.of("engine could not be created: " + e),
                    observations, null, null, null, null);
        }
        try {
            Run run = engine.start(c.requirement(), c.scenario(), RunOptions.defaults().withAutonomy(c.autonomy())
                    .withWorkspace(c.workspace()), operator);
            Outcome outcome = drive(engine, run.id(), c.expectations(), observations);
            Run finished = engine.get(run.id());
            EvaluationReport report = RunEvaluator.evaluate(finished, engineFactory.index(c));
            List<String> failures = new ArrayList<>(outcome.problems());
            failures.addAll(check(c.expectations(), finished, report, outcome.sawClarification()));
            observations.add("final status " + finished.status() + (finished.haltReason() == null ? ""
                    : " (" + finished.haltReason() + ")") + ", risk " + finished.currentRisk().level()
                    + ", overall score " + Math.round(report.overall() * 100) + "%");
            return new SuiteResult.CaseResult(c.id(), failures.isEmpty(), failures, observations, finished.id(),
                    finished.status(), finished.haltReason(), report);
        } catch (RuntimeException e) {
            LOG.warn("evaluation case failed case={} error={}", c.id(), e.toString());
            return new SuiteResult.CaseResult(c.id(), false, List.of("case aborted: " + e), observations, null, null,
                    null, null);
        } finally {
            engine.close();
        }
    }

    private record Outcome(boolean sawClarification, List<String> problems) {
    }

    private Outcome drive(OrchestrationEngine engine, String runId, Expectations expectations,
                          List<String> observations) {
        long deadline = System.nanoTime() + caseTimeout.toNanos();
        boolean sawClarification = false;
        int actions = 0;
        List<String> problems = new ArrayList<>();
        while (true) {
            Run run = engine.get(runId);
            if (run.status().isTerminal() || run.status() == RunStatus.SAFE_STOPPED) {
                break;
            }
            boolean clarification = run.stages().stream()
                    .anyMatch(s -> s.status() == StageStatus.AWAITING_CLARIFICATION);
            if (run.status() == RunStatus.WAITING_HUMAN && (clarification || !run.pendingApprovals().isEmpty())) {
                sawClarification |= clarification;
                if (!expectations.drivesToCompletion()) {
                    observations.add("stopped at the first human gate (" + (clarification ? "clarification"
                            : "approval") + ")");
                    break;
                }
                if (++actions > MAX_ACTIONS) {
                    problems.add("more than " + MAX_ACTIONS + " governance actions; stopped");
                    break;
                }
                if (clarification) {
                    engine.clarify(runId, operator, suggestedAnswers(run));
                    observations.add("answered " + run.clarification().questions().size()
                            + " clarification questions with the first suggested answers");
                    continue;
                }
                List<Approval> pending = run.pendingApprovals();
                if (pending.stream().anyMatch(a -> a.ruleIds().contains(NoGoReleaseRule.ID))) {
                    observations.add("stopped at the NO_GO release gate; the runner never waives it");
                    break;
                }
                for (Approval approval : pending) {
                    engine.approve(runId, approval.stageId(), operator, APPROVAL_COMMENT, approval.id());
                    observations.add("approved the " + approval.kind() + " gate of " + approval.stageId()
                            + " (" + String.join(", ", approval.ruleIds()) + ")");
                }
                continue;
            }
            if (System.nanoTime() > deadline) {
                problems.add("timed out after " + caseTimeout + " with the run " + run.status());
                break;
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
        return new Outcome(sawClarification, problems);
    }

    private static Map<String, String> suggestedAnswers(Run run) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (ClarificationQuestion question : Objects.requireNonNull(run.clarification()).questions()) {
            answers.put(question.id(), question.suggestedAnswers().isEmpty() ? "No preference"
                    : question.suggestedAnswers().getFirst());
        }
        return answers;
    }

    /**
     * Checks the expectations of a case against the finished run and its evaluation.
     *
     * @param expectations the expectations
     * @param run the finished run
     * @param report its evaluation
     * @param sawClarification whether the runner saw the run wait for clarification
     * @return the expectations that did not hold
     */
    static List<String> check(Expectations expectations, Run run, EvaluationReport report, boolean sawClarification) {
        List<String> failures = new ArrayList<>();
        boolean clarified = sawClarification || run.clarification() != null;
        if (expectations.expectClarification() != null && expectations.expectClarification() != clarified) {
            failures.add("expected " + (expectations.expectClarification() ? "a" : "no") + " clarification request");
        }
        boolean denied = run.haltReason() == HaltReason.POLICY_DENIED;
        if (expectations.expectPolicyDenial() != null && expectations.expectPolicyDenial() != denied) {
            failures.add("expected " + (expectations.expectPolicyDenial() ? "a" : "no") + " policy denial, run "
                    + run.status() + (run.haltReason() == null ? "" : " (" + run.haltReason() + ")"));
        }
        boolean completed = run.status() == RunStatus.COMPLETED;
        if (expectations.expectCompleted() != null && expectations.expectCompleted() != completed) {
            failures.add("expected the run " + (expectations.expectCompleted() ? "to complete" : "not to complete")
                    + ", it is " + run.status());
        }
        if (expectations.expectedRiskAtLeast() != null
                && !run.currentRisk().level().atLeast(expectations.expectedRiskAtLeast())) {
            failures.add("expected risk at least " + expectations.expectedRiskAtLeast() + ", run risk is "
                    + run.currentRisk().level());
        }
        if (expectations.minOverallScore() != null && report.overall() < expectations.minOverallScore()) {
            failures.add("expected an overall score of at least " + expectations.minOverallScore() + ", got "
                    + report.overall());
        }
        if (expectations.expectedReleaseDecision() != null) {
            ReleaseReadiness.GoNoGo decision = latestRelease(run).map(ReleaseReadiness::decision).orElse(null);
            if (decision != expectations.expectedReleaseDecision()) {
                failures.add("expected release decision " + expectations.expectedReleaseDecision() + ", got "
                        + decision);
            }
        }
        return failures;
    }

    /** The accepted release readiness, or the latest one of the current plan (for example under NO_GO review). */
    private static Optional<ReleaseReadiness> latestRelease(Run run) {
        Optional<ReleaseReadiness> accepted = run.acceptedPayload(ReleaseReadiness.class);
        if (accepted.isPresent()) {
            return accepted;
        }
        return run.artifacts().stream().filter(a -> a.planVersion() == run.planVersion()
                        && a.payload() instanceof ReleaseReadiness)
                .max(Comparator.comparingInt(Artifact::version)).map(a -> (ReleaseReadiness) a.payload());
    }
}
