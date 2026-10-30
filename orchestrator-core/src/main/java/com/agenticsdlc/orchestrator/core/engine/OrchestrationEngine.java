package com.agenticsdlc.orchestrator.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.FaultInjectingStageAgent;
import com.agenticsdlc.orchestrator.core.agent.LlmTrace;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.ActorType;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.metrics.MetricsCalculator;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalKind;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.Checkpoint;
import com.agenticsdlc.orchestrator.core.model.Clarification;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.Compensation;
import com.agenticsdlc.orchestrator.core.model.CompensationStatus;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.PolicyFindingRecord;
import com.agenticsdlc.orchestrator.core.model.RejectDisposition;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunBudget;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyDecision;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.policy.rules.NoGoReleaseRule;
import com.agenticsdlc.orchestrator.core.port.RunStore;
import com.agenticsdlc.orchestrator.core.util.Clocks;
import com.agenticsdlc.orchestrator.core.util.Ids;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * The agentic SDLC orchestration engine.
 *
 * <p>Executes a {@link WorkflowGraph} per run: stages whose predecessors succeeded pass an entry gate and
 * run on virtual threads (independent branches in parallel, joins wait for all predecessors); agent outputs
 * are validated, hashed, recorded with provenance (decision lineage) and pass an exit gate (policy rules,
 * gate approval requirement, autonomy) <em>before</em> they are accepted: an artifact that requires approval is
 * accepted only when the approval is granted, and a denied artifact is removed from the history. Retryable
 * failures are retried with exponential backoff, exhausted primary agents fall back to the deterministic agent,
 * and unrecoverable failures roll back to the stage's checkpoint (saga compensations in reverse order) and
 * safe-stop the run. A failed verification feeds back to implementation (bounded loop). Humans approve, reject,
 * clarify, re-plan, stop and resume through the commands below. Budgets bound agent calls, tokens, active time,
 * feedback loops and re-plans.</p>
 *
 * <p>Budgets are checked when an attempt is submitted, against the counters at that moment. Agent calls are
 * counted at submission, but tokens and active time are counted when an attempt finishes, and parallel branches
 * are submitted independently: a run can therefore overshoot the token and active time budgets by what up to the
 * number of concurrently running branches consume (for example both branches after {@code design} start while
 * little token budget is left); the next submission then safe-stops the run with BUDGET_EXCEEDED.</p>
 *
 * <p>Threading and persistence: every mutation of a run happens under its lock; agent attempts run off the lock
 * and re-acquire it on completion. After every mutation the run and the buffered audit events are handed to
 * {@link RunStore#commit} (by default {@code save} then {@link AuditSink#append}; a transactional store overrides
 * it to persist both atomically) while still holding the lock. When the commit fails, the exception reaches the
 * caller of the command; the in-memory run keeps the mutation, so it may be ahead of the store (and the audit log)
 * until the next successful commit, which persists the full current state. A failed {@code start} unregisters
 * the new run, so no run that was never persisted stays visible. {@link #get} and {@link #list} return copies built
 * under the lock.</p>
 *
 * <p>Commands throw {@link RunNotFoundException} for unknown runs, {@link InvalidRunStateException} (an
 * {@link IllegalStateException}) when the run or stage is in the wrong state, {@link InvalidCommandException}
 * for invalid input, and a plain {@link IllegalStateException} when the engine is closed or shutting down.</p>
 */
public final class OrchestrationEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OrchestrationEngine.class);

    /** Agent and provider id used for artifacts the engine itself produces (release finalization). */
    public static final String ENGINE_AGENT_ID = MetricsCalculator.ENGINE_PROVIDER;

    /** Replacement for an agent rationale that contains a credential-like token (never audited verbatim). */
    public static final String REDACTED_RATIONALE = "[rationale redacted: contained a credential-like token]";

    /** How often a failed retry wake-up is itself retried before the stage fails. */
    static final int MAX_WAKE_UP_RETRIES = 3;

    private final WorkflowGraph graph;
    private final AgentRegistry agents;
    private final RunStore runStore;
    private final AuditSink auditSink;
    private final MetricsRecorder metrics;
    private final EngineConfig config;
    private final JsonCodec codec = JsonCodec.standard();
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService retryScheduler;
    private final boolean ownsScheduler;
    private final RunRegistry registry;
    private final Scheduler scheduler;
    private final GateEvaluator gates;
    private final StageExecutor stageExecutor;
    private final RunControl control;
    private final ReplanService replanService;
    private final RollbackService rollbackService;
    private volatile boolean closed;

    /**
     * Creates the engine.
     *
     * @param graph the workflow graph
     * @param agents the agent registry (must contain a deterministic agent for every role of the graph)
     * @param policyEngine the policy engine
     * @param runStore the run store
     * @param auditSink the audit sink
     * @param metrics the metrics recorder
     * @param config the configuration
     * @throws IllegalArgumentException when a role of the graph has no deterministic agent
     */
    public OrchestrationEngine(WorkflowGraph graph, AgentRegistry agents, PolicyEngine policyEngine, RunStore runStore,
                               AuditSink auditSink, MetricsRecorder metrics, EngineConfig config) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(policyEngine, "policyEngine");
        for (StageDefinition stage : graph.stages()) {
            if (!agents.hasDeterministic(stage.role())) {
                throw new IllegalArgumentException("No deterministic agent registered for role " + stage.role()
                        + " (stage " + stage.id() + ")");
            }
        }
        this.ownsExecutor = config.stageExecutor() == null;
        this.executor = ownsExecutor
                ? Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sdlc-stage-", 0).factory())
                : config.stageExecutor();
        this.ownsScheduler = config.retryScheduler() == null;
        this.retryScheduler = ownsScheduler
                ? Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("sdlc-retry-scheduler").daemon(true).factory())
                : config.retryScheduler();
        this.registry = new RunRegistry(runStore::load);
        this.scheduler = new Scheduler(graph);
        this.gates = new GateEvaluator(policyEngine);
        this.stageExecutor = new StageExecutor(graph, config, executor);
        this.control = new RunControl();
        this.replanService = new ReplanService(graph, control);
        List<CompensationHandler> handlers = new ArrayList<>(config.compensationHandlers());
        if (handlers.stream().noneMatch(h -> WorkspaceSandbox.DELETE_ACTION.equals(h.action()))) {
            handlers.add(new SandboxCompensationHandler(config.sandboxRoot()));
        }
        this.rollbackService = new RollbackService(handlers, control, metrics);
    }

    /**
     * Returns the workflow graph.
     *
     * @return the graph
     */
    public WorkflowGraph workflowGraph() {
        return graph;
    }

    // =========================================================================================== commands

    /**
     * Starts a run. The run is created (RUN_CREATED) and the RUN_START policy is evaluated on the
     * requirement: a DENY safe-stops the new run with {@link HaltReason#POLICY_DENIED} (the denial is visible
     * and auditable, not an error); otherwise the run becomes RUNNING and the root stages are scheduled.
     *
     * @param requirement requirement text (not blank)
     * @param scenarioKey scenario key (blank becomes {@code custom})
     * @param options run options
     * @return a snapshot copy of the run
     * @throws InvalidCommandException for a blank requirement, an unknown provider or faults for unknown stages
     */
    public Run start(String requirement, String scenarioKey, RunOptions options) {
        return start(requirement, scenarioKey, options, Actor.system());
    }

    /**
     * Starts a run on behalf of an identified requester. Identical to {@link #start(String, String, RunOptions)}
     * except that the RUN_CREATED audit event is attributed to {@code requestedBy} (normally the human operator
     * identified by the control plane) instead of the system actor.
     *
     * @param requirement requirement text (not blank)
     * @param scenarioKey scenario key (blank becomes {@code custom})
     * @param options run options
     * @param requestedBy the actor on whose behalf the run is created
     * @return a snapshot copy of the run
     * @throws InvalidCommandException for a blank requirement, an unknown provider or faults for unknown stages
     * @throws RuntimeException whatever the store or audit sink throws when the first commit fails; the new run is
     *                          then unregistered (no ghost run remains in {@link #list()}) and in-flight work of
     *                          the run is cancelled
     */
    public Run start(String requirement, String scenarioKey, RunOptions options, Actor requestedBy) {
        ensureOpen();
        Objects.requireNonNull(requestedBy, "requestedBy");
        if (requirement == null || requirement.isBlank()) {
            throw new InvalidCommandException("requirement must not be blank");
        }
        Objects.requireNonNull(options, "options");
        if (!agents.hasProvider(options.providerId())) {
            throw new InvalidCommandException("unknown agent provider: " + options.providerId());
        }
        for (FaultSpec fault : options.faults()) {
            if (!graph.contains(fault.stageId())) {
                throw new InvalidCommandException("fault targets unknown stage: " + fault.stageId());
            }
        }
        String scenario = scenarioKey == null || scenarioKey.isBlank() ? "custom" : scenarioKey;
        Run run = Run.create(Ids.newId(), graph, scenario, requirement.strip(), options, now());
        registry.register(run);
        LOG.info("run created runId={} scenario={} autonomy={} provider={} workspace={}", run.id(), scenario,
                options.autonomy(), providerLabel(options.providerId()), options.workspace());
        LOG.debug("run requirement runId={} requirement={}", run.id(), run.requirement());
        try {
            return createRun(run, scenario, options, requestedBy);
        } catch (RuntimeException e) {
            RunRegistry.RunHolder removed = registry.remove(run.id());
            if (removed != null) {
                removed.lock.lock();
                try {
                    removed.inFlight.values().forEach(f -> f.future().cancel(true));
                    removed.inFlight.clear();
                } finally {
                    removed.lock.unlock();
                }
            }
            LOG.error("run creation failed and the run was discarded runId={} error={}", run.id(), e.toString());
            throw e;
        }
    }

    private Run createRun(Run run, String scenario, RunOptions options, Actor requestedBy) {
        return mutate(run.id(), tx -> {
            tx.audit(AuditEventType.RUN_CREATED, null, requestedBy, "run created for scenario " + scenario,
                    RunMutation.data("scenario", scenario, "autonomy", options.autonomy(), "provider",
                            providerLabel(options.providerId()), "workspace", options.workspace(),
                            "requestedBy", requestedBy.id(),
                            "requirementChars", run.requirement().length(), "faults",
                            options.faults().stream().map(f -> f.stageId() + ":" + f.kind() + "x" + f.failTimes())
                                    .toList()));
            PolicyDecision decision = gates.runStart(run, run.activeRequirement());
            recordFindings(tx, null, GatePhase.RUN_START, decision.findings());
            if (decision.verdict() == Verdict.DENY) {
                String detail = denialDetail(decision.findings());
                tx.audit(AuditEventType.POLICY_DENIED, null, Actor.system(), "requirement denied by policy: " + detail,
                        RunMutation.data("phase", GatePhase.RUN_START, "rules", ruleIds(decision.findings())));
                control.safeStop(tx, HaltReason.POLICY_DENIED, "policy denied the requirement: " + detail,
                        Actor.system());
                return;
            }
            run.markStarted(tx.now);
        });
    }

    /**
     * Approves the pending gate of a stage without checking which approval the operator reviewed. Same as
     * {@link #approve(String, String, Actor, String, String)} with a null {@code expectedApprovalId}.
     *
     * @param runId run id
     * @param stageId stage id
     * @param actor a human actor
     * @param comment optional comment (required as waiver for NO_GO releases)
     * @return a snapshot copy of the run
     */
    public Run approve(String runId, String stageId, Actor actor, @Nullable String comment) {
        return approve(runId, stageId, actor, comment, null);
    }

    /**
     * Approves the pending gate of a stage. Entry approval: the stage starts (checkpoint, attempt). Exit
     * approval: the artifact under review is accepted now (ARTIFACT_ACCEPTED; downstream stages are invalidated
     * when it differs from the previously accepted version), the stage SUCCEEDED and downstream stages are
     * scheduled. Approving a NO_GO release ({@code CHG-003}) requires a waiver comment.
     *
     * @param runId run id
     * @param stageId stage id
     * @param actor a human actor
     * @param comment optional comment (required as waiver for NO_GO releases)
     * @param expectedApprovalId id of the approval the operator reviewed; when non-null it must be the stage's
     *                           pending approval (binds the decision to what was reviewed)
     * @return a snapshot copy of the run
     * @throws InvalidRunStateException when the run is not active, the stage is not awaiting approval, or the
     *                                  pending approval is not {@code expectedApprovalId}
     */
    public Run approve(String runId, String stageId, Actor actor, @Nullable String comment,
                       @Nullable String expectedApprovalId) {
        requireHuman(actor, "approvals");
        return mutate(runId, tx -> {
            Run run = tx.run;
            requireActive(run);
            StageDefinition def = requireStage(stageId);
            StageExecution exec = run.stage(stageId);
            Approval pending = requirePendingApproval(exec, expectedApprovalId);
            if (pending.ruleIds().contains(NoGoReleaseRule.ID) && (comment == null || comment.isBlank())) {
                throw new InvalidCommandException("approving a NO_GO release requires a waiver comment");
            }
            Artifact reviewed = null;
            if (pending.kind() == ApprovalKind.EXIT && pending.artifactVersion() != null) {
                reviewed = run.artifact(stageId, pending.artifactVersion()).orElseThrow(() ->
                        new InvalidRunStateException("artifact " + stageId + "@v" + pending.artifactVersion()
                                + " under review is no longer in the history"));
            }
            Approval approved = pending.approve(actor.id(), comment == null || comment.isBlank() ? null
                    : comment.strip(), tx.now);
            run.replaceApproval(approved);
            run.updateCounters(RunCounters::plusApprovalApproved);
            long decisionMs = Clocks.millisBetween(pending.requestedAt(), tx.now);
            safeMetric(() -> metrics.approvalDecision(decisionMs, true));
            tx.audit(AuditEventType.APPROVAL_GRANTED, stageId, actor, pending.kind() + " gate of " + stageId
                            + " approved by " + actor.id(),
                    RunMutation.data("approvalId", pending.id(), "kind", pending.kind(), "comment", comment,
                            "decisionMs", decisionMs, "artifactVersion", pending.artifactVersion(), "artifactHash",
                            pending.artifactHash()));
            if (pending.kind() == ApprovalKind.ENTRY) {
                startActivation(tx, def, exec);
            } else {
                if (reviewed != null && !Objects.equals(run.acceptedVersions().get(stageId), reviewed.version())) {
                    acceptArtifact(tx, def, reviewed, pending.id());
                }
                markSucceeded(tx, exec);
            }
        });
    }

    /**
     * Rejects the pending gate of a stage without checking which approval the operator reviewed. Same as
     * {@link #reject(String, String, Actor, String, RejectDisposition, String)} with a null
     * {@code expectedApprovalId}.
     *
     * @param runId run id
     * @param stageId stage id
     * @param actor a human actor
     * @param reason rejection reason (not blank)
     * @param disposition REWORK or ABORT
     * @return a snapshot copy of the run
     */
    public Run reject(String runId, String stageId, Actor actor, String reason, RejectDisposition disposition) {
        return reject(runId, stageId, actor, reason, disposition, null);
    }

    /**
     * Rejects the pending gate of a stage. REWORK counts as a feedback loop (rejected when the feedback loop
     * budget is exhausted) and appends the reason to the stage's feedback: for an exit gate the stage re-runs
     * (the artifact under review was never accepted, so nothing needs restoring); for an entry gate the stage
     * returns to PENDING/STALE without running, so its entry gate is evaluated again and approval is requested
     * again before it runs with the feedback. ABORT: the run is ABORTED (pending and in-flight work is cancelled
     * and registered compensations are executed).
     *
     * @param runId run id
     * @param stageId stage id
     * @param actor a human actor
     * @param reason rejection reason (not blank)
     * @param disposition REWORK or ABORT
     * @param expectedApprovalId id of the approval the operator reviewed; when non-null it must be the stage's
     *                           pending approval
     * @return a snapshot copy of the run
     * @throws InvalidRunStateException when the run is not active, the stage is not awaiting approval, or the
     *                                  pending approval is not {@code expectedApprovalId}
     */
    public Run reject(String runId, String stageId, Actor actor, String reason, RejectDisposition disposition,
                      @Nullable String expectedApprovalId) {
        requireHuman(actor, "rejections");
        if (reason == null || reason.isBlank()) {
            throw new InvalidCommandException("a rejection reason is required");
        }
        Objects.requireNonNull(disposition, "disposition");
        return mutate(runId, tx -> {
            Run run = tx.run;
            requireActive(run);
            StageDefinition def = requireStage(stageId);
            StageExecution exec = run.stage(stageId);
            Approval pending = requirePendingApproval(exec, expectedApprovalId);
            if (disposition == RejectDisposition.REWORK
                    && run.counters().feedbackLoops() >= run.budget().maxFeedbackLoops()) {
                throw new InvalidCommandException("feedback loop budget exhausted (" + run.budget().maxFeedbackLoops()
                        + "); reject with ABORT or re-plan");
            }
            run.replaceApproval(pending.reject(actor.id(), reason.strip(), tx.now));
            run.updateCounters(RunCounters::plusApprovalRejected);
            long decisionMs = Clocks.millisBetween(pending.requestedAt(), tx.now);
            safeMetric(() -> metrics.approvalDecision(decisionMs, false));
            tx.audit(AuditEventType.APPROVAL_REJECTED, stageId, actor, pending.kind() + " gate of " + stageId
                            + " rejected by " + actor.id() + " (" + disposition + "): " + reason.strip(),
                    RunMutation.data("approvalId", pending.id(), "kind", pending.kind(), "disposition", disposition,
                            "reason", reason.strip()));
            if (disposition == RejectDisposition.ABORT) {
                abortRun(tx, actor, "rejected at " + stageId + ": " + reason.strip());
                return;
            }
            run.updateCounters(RunCounters::plusFeedbackLoop);
            exec.addFeedback("[review by " + actor.id() + "] " + reason.strip());
            tx.audit(AuditEventType.FEEDBACK_LOOP, stageId, actor, "rework requested for " + stageId,
                    RunMutation.data("source", "human review", "loop", run.counters().feedbackLoops(), "max",
                            run.budget().maxFeedbackLoops(), "feedback", reason.strip()));
            if (pending.kind() == ApprovalKind.ENTRY) {
                // Do not start the stage: back to PENDING/STALE so the entry gate is evaluated again (by the
                // pump at the end of this mutation) and approval is requested again for the reworked entry.
                control.resetToReady(tx, exec, "entry rework requested by " + actor.id(), actor);
            } else {
                exec.setPendingApproval(null);
                exec.setAttempts(0);
                exec.setActiveAgent(null);
                submitAttempt(tx, def, exec);
            }
        });
    }

    /**
     * Answers the open clarification questions; the asking stage re-runs with the answers. Every question
     * must be answered and only known question ids are accepted.
     *
     * @param runId run id
     * @param actor a human actor
     * @param answers answers by question id
     * @return a snapshot copy of the run
     * @throws InvalidRunStateException when the run is not active or nothing awaits clarification
     */
    public Run clarify(String runId, Actor actor, Map<String, String> answers) {
        requireHuman(actor, "clarifications");
        Objects.requireNonNull(answers, "answers");
        return mutate(runId, tx -> {
            Run run = tx.run;
            requireActive(run);
            StageExecution exec = run.stages().stream()
                    .filter(s -> s.status() == StageStatus.AWAITING_CLARIFICATION).findFirst()
                    .map(s -> run.stage(s.stageId()))
                    .orElseThrow(() -> new InvalidRunStateException("no stage is awaiting clarification"));
            Clarification clarification = run.clarification();
            if (clarification == null || clarification.answered()) {
                throw new InvalidRunStateException("there are no open clarification questions");
            }
            Set<String> questionIds = clarification.questions().stream().map(ClarificationQuestion::id)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            List<String> unknown = answers.keySet().stream().filter(k -> !questionIds.contains(k)).sorted().toList();
            if (!unknown.isEmpty()) {
                throw new InvalidCommandException("unknown question ids: " + String.join(", ", unknown));
            }
            List<String> missing = questionIds.stream()
                    .filter(q -> answers.get(q) == null || answers.get(q).isBlank()).toList();
            if (!missing.isEmpty()) {
                throw new InvalidCommandException("every open question must be answered; missing: "
                        + String.join(", ", missing));
            }
            Map<String, String> ordered = new LinkedHashMap<>();
            questionIds.forEach(q -> ordered.put(q, answers.get(q).strip()));
            run.setClarification(clarification.answer(ordered, actor.id(), tx.now));
            tx.audit(AuditEventType.CLARIFICATION_PROVIDED, exec.stageId(), actor,
                    ordered.size() + " clarification answers provided by " + actor.id(),
                    RunMutation.data("questionIds", List.copyOf(ordered.keySet())));
            exec.setAttempts(0);
            submitAttempt(tx, graph.stage(exec.stageId()), exec);
        });
    }

    /**
     * Re-plans the run with a changed requirement (RUNNING, WAITING_HUMAN or SAFE_STOPPED runs, within the
     * re-plan budget). The new requirement passes the RUN_START policy first (a denial safe-stops the run with
     * POLICY_DENIED). Then: the active requirement changes, the plan version increments, every stage from
     * the roots is invalidated (STALE/PENDING, in-flight attempts cancelled, approvals revoked, artifact
     * history kept), the halt is cleared and the run resumes.
     *
     * @param runId run id
     * @param actor a human actor
     * @param changedRequirement the new requirement (not blank)
     * @return a snapshot copy of the run
     * @throws InvalidRunStateException when the run is COMPLETED or ABORTED
     */
    public Run replan(String runId, Actor actor, String changedRequirement) {
        requireHuman(actor, "re-planning");
        if (changedRequirement == null || changedRequirement.isBlank()) {
            throw new InvalidCommandException("the changed requirement must not be blank");
        }
        String requirement = changedRequirement.strip();
        return mutate(runId, tx -> {
            Run run = tx.run;
            if (run.status().isTerminal()) {
                throw new InvalidRunStateException("cannot re-plan a " + run.status() + " run");
            }
            if (run.counters().replans() >= run.budget().maxReplans()) {
                throw new InvalidCommandException("re-plan budget exhausted (" + run.budget().maxReplans() + ")");
            }
            PolicyDecision decision = gates.runStart(run, requirement);
            recordFindings(tx, null, GatePhase.RUN_START, decision.findings());
            if (decision.verdict() == Verdict.DENY) {
                String detail = denialDetail(decision.findings());
                tx.audit(AuditEventType.POLICY_DENIED, null, actor, "re-plan denied by policy: " + detail,
                        RunMutation.data("phase", GatePhase.RUN_START, "rules", ruleIds(decision.findings()),
                                "requirementChars", requirement.length()));
                if (run.status().isActive()) {
                    control.safeStop(tx, HaltReason.POLICY_DENIED, "re-plan denied by policy: " + detail, actor);
                } else {
                    run.setHalt(HaltReason.POLICY_DENIED, "re-plan denied by policy: " + detail);
                }
                return;
            }
            LOG.info("run re-planned runId={} planVersion={}", run.id(), run.planVersion() + 1);
            LOG.debug("re-plan requirement runId={} requirement={}", run.id(), requirement);
            replanService.replan(tx, requirement, actor);
            run.setHalt(null, null);
            run.setStatus(RunStatus.RUNNING);
            run.markStarted(tx.now);
        });
    }

    /**
     * Stops a RUNNING or WAITING_HUMAN run: in-flight attempts are cancelled (their stages return to
     * PENDING/STALE), pending approvals and scheduled retries are kept, and the run is SAFE_STOPPED with
     * {@link HaltReason#OPERATOR_STOP}.
     *
     * @param runId run id
     * @param actor a human actor
     * @param reason optional reason
     * @return a snapshot copy of the run
     * @throws InvalidRunStateException when the run is not RUNNING or WAITING_HUMAN
     */
    public Run stop(String runId, Actor actor, String reason) {
        requireHuman(actor, "stopping a run");
        String detail = reason == null || reason.isBlank() ? "stopped by " + actor.id() : reason.strip();
        return mutate(runId, tx -> {
            if (!tx.run.status().isActive()) {
                throw new InvalidRunStateException("only RUNNING or WAITING_HUMAN runs can be stopped (status "
                        + tx.run.status() + ")");
            }
            control.safeStop(tx, HaltReason.OPERATOR_STOP, detail, actor);
        });
    }

    /**
     * Resumes a SAFE_STOPPED run (not after a policy denial, which needs a re-plan): FAILED stages are reset to
     * PENDING with zero attempts, the halt is cleared, scheduled retries are re-armed and ready stages run.
     *
     * @param runId run id
     * @param actor a human actor
     * @return a snapshot copy of the run
     * @throws InvalidRunStateException when the run is not SAFE_STOPPED or was stopped by a policy denial
     */
    public Run resume(String runId, Actor actor) {
        requireHuman(actor, "resuming a run");
        return mutate(runId, tx -> {
            Run run = tx.run;
            if (run.status() != RunStatus.SAFE_STOPPED) {
                throw new InvalidRunStateException("only SAFE_STOPPED runs can be resumed (status " + run.status()
                        + ")");
            }
            if (run.haltReason() == HaltReason.POLICY_DENIED) {
                throw new InvalidRunStateException("a policy denial cannot be resumed; re-plan with a changed "
                        + "requirement");
            }
            List<String> reset = new ArrayList<>();
            for (StageExecution exec : run.stages()) {
                StageExecution live = run.stage(exec.stageId());
                if (live.status() == StageStatus.FAILED) {
                    live.reset(StageStatus.PENDING);
                    reset.add(live.stageId());
                }
            }
            HaltReason previous = run.haltReason();
            boolean budgetExtended = false;
            if (previous == HaltReason.BUDGET_EXCEEDED) {
                // The explicit human resume is the authorization to spend one more default allowance.
                run.setBudget(run.budget().extended());
                budgetExtended = true;
            }
            run.setHalt(null, null);
            run.setStatus(RunStatus.RUNNING);
            run.markStarted(tx.now);
            tx.audit(AuditEventType.RUN_RESUMED, null, actor, "run resumed by " + actor.id(),
                    RunMutation.data("previousHaltReason", previous, "resetStages", reset, "budgetExtended",
                            budgetExtended, "budget", run.budget()));
            for (StageExecution exec : run.stages()) {
                StageExecution live = run.stage(exec.stageId());
                if (live.status() == StageStatus.RETRY_SCHEDULED) {
                    long token = tx.holder.nextToken();
                    live.setToken(token);
                    Instant at = live.nextAttemptAt();
                    long delay = at == null ? 0L : Math.max(0L, Clocks.millisBetween(tx.now, at));
                    scheduleRetry(run.id(), live.stageId(), token, Duration.ofMillis(delay));
                }
            }
        });
    }

    /**
     * Returns a snapshot copy of a run, safe to read and serialize.
     *
     * @param runId run id
     * @return the copy
     * @throws RunNotFoundException when unknown
     */
    public Run get(String runId) {
        return registry.withLock(runId, holder -> holder.run.copy());
    }

    /**
     * Returns snapshot copies of all runs known to the engine, newest first.
     *
     * @return copies
     */
    public List<Run> list() {
        List<Run> runs = new ArrayList<>();
        for (RunRegistry.RunHolder holder : registry.all()) {
            holder.lock.lock();
            try {
                runs.add(holder.run.copy());
            } finally {
                holder.lock.unlock();
            }
        }
        runs.sort(Comparator.comparing(Run::createdAt).reversed().thenComparing(Run::id));
        return List.copyOf(runs);
    }

    /**
     * Loads all persisted runs and recovers runs interrupted by a restart: every RUNNING run gets its RUNNING
     * stages reset to PENDING (STALE when an accepted artifact exists) and is SAFE_STOPPED with
     * {@link HaltReason#INTERRUPTED_BY_RESTART} (audited RUN_INTERRUPTED and SAFE_STOP). Runs waiting for a
     * human, stopped or terminal are only registered. Call once at startup.
     */
    public void recoverInterrupted() {
        ensureOpen();
        int recovered = 0;
        for (Run loaded : runStore.loadAll()) {
            RunRegistry.RunHolder holder = registry.register(loaded);
            boolean live;
            holder.lock.lock();
            try {
                live = !holder.inFlight.isEmpty() || holder.run.status() != RunStatus.RUNNING;
            } finally {
                holder.lock.unlock();
            }
            if (live) {
                continue;
            }
            mutate(loaded.id(), tx -> {
                Run run = tx.run;
                if (run.status() != RunStatus.RUNNING) {
                    return;
                }
                List<String> reset = new ArrayList<>();
                for (StageExecution exec : run.stages()) {
                    if (exec.status() == StageStatus.RUNNING) {
                        control.resetToReady(tx, run.stage(exec.stageId()), "interrupted by engine restart",
                                Actor.system());
                        reset.add(exec.stageId());
                    }
                }
                tx.audit(AuditEventType.RUN_INTERRUPTED, null, Actor.system(),
                        "run interrupted by an engine restart; " + reset.size() + " in-flight stages reset",
                        RunMutation.data("resetStages", reset));
                run.setStatus(RunStatus.SAFE_STOPPED);
                run.setHalt(HaltReason.INTERRUPTED_BY_RESTART, "engine restarted while the run was in progress");
                tx.audit(AuditEventType.SAFE_STOP, null, Actor.system(), "run safe-stopped: INTERRUPTED_BY_RESTART",
                        RunMutation.data("haltReason", HaltReason.INTERRUPTED_BY_RESTART, "resetStages", reset));
            });
            recovered++;
        }
        LOG.info("recovery finished interruptedRuns={}", recovered);
    }

    /**
     * Stops accepting commands, cancels in-flight attempts and shuts down engine-owned executors. Runs that
     * were in progress stay RUNNING in the store and are recovered by {@link #recoverInterrupted()} on the
     * next start.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (RunRegistry.RunHolder holder : registry.all()) {
            holder.lock.lock();
            try {
                holder.inFlight.values().forEach(f -> f.future().cancel(true));
                holder.inFlight.clear();
            } finally {
                holder.lock.unlock();
            }
        }
        if (ownsScheduler) {
            retryScheduler.shutdownNow();
        }
        if (ownsExecutor) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("stage executor did not terminate within 5 s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("orchestration engine closed");
    }

    /**
     * Alias of {@link #close()}.
     */
    public void shutdown() {
        close();
    }

    // =========================================================================================== mutation core

    private Run mutate(String runId, Consumer<RunMutation> body) {
        ensureOpen();
        RunRegistry.RunHolder holder = registry.holder(runId);
        Run copy;
        holder.lock.lock();
        try {
            RunMutation tx = new RunMutation(holder, now());
            body.accept(tx);
            pump(tx);
            tx.run.touch(tx.now);
            runStore.commit(tx.run, tx.events(), auditSink);
            copy = tx.run.copy();
        } finally {
            holder.lock.unlock();
        }
        notifyListener(copy);
        return copy;
    }

    private void notifyListener(Run copy) {
        EngineListener listener = config.listener();
        if (listener != null) {
            try {
                listener.onRunChanged(copy);
            } catch (RuntimeException e) {
                LOG.warn("engine listener failed runId={} error={}", copy.id(), e.toString());
            }
        }
    }

    /**
     * Drives the run forward: completes it when every stage succeeded, otherwise evaluates the entry gate of
     * every ready stage (PENDING/STALE with all predecessors SUCCEEDED) and starts, gates or fails it, then
     * updates the RUNNING/WAITING_HUMAN status and human-wait intervals.
     */
    private void pump(RunMutation tx) {
        Run run = tx.run;
        if (run.status().isActive()) {
            if (scheduler.allSucceeded(run)) {
                completeRun(tx);
            } else {
                for (StageDefinition def : scheduler.readyStages(run)) {
                    if (!run.status().isActive()) {
                        break;
                    }
                    StageExecution exec = run.stage(def.id());
                    if (!exec.status().isSchedulable()) {
                        continue;
                    }
                    tx.audit(AuditEventType.STAGE_READY, def.id(), Actor.system(), "stage " + def.id() + " is ready",
                            RunMutation.data("previousStatus", exec.status()));
                    GateEvaluator.GateDecision gate = gates.entry(run, def);
                    recordFindings(tx, def.id(), GatePhase.ENTRY, gate.findings());
                    auditGate(tx, def.id(), GatePhase.ENTRY, gate);
                    switch (gate.verdict()) {
                        case DENY -> {
                            exec.setStatus(StageStatus.FAILED);
                            exec.setLastError("entry gate denied: " + gate.reasonText());
                            tx.audit(AuditEventType.POLICY_DENIED, def.id(), Actor.system(),
                                    "entry of " + def.id() + " denied: " + gate.reasonText(),
                                    RunMutation.data("phase", GatePhase.ENTRY, "rules", gate.ruleIds()));
                            tx.audit(AuditEventType.STAGE_FAILED, def.id(), Actor.system(), exec.lastError());
                            rollbackService.rollback(tx, def.id(), "entry gate denied: " + gate.reasonText(),
                                    HaltReason.POLICY_DENIED);
                        }
                        case REQUIRE_APPROVAL -> requestApproval(tx, exec, ApprovalKind.ENTRY, gate, null, null, null);
                        case ALLOW -> startActivation(tx, def, exec);
                    }
                }
            }
        }
        updateWaitingState(tx);
    }

    private void updateWaitingState(RunMutation tx) {
        Run run = tx.run;
        if (run.status().isActive()) {
            boolean inProgress = scheduler.anyInProgress(run);
            boolean awaiting = scheduler.anyAwaitingHuman(run);
            RunStatus target = !inProgress && awaiting ? RunStatus.WAITING_HUMAN : RunStatus.RUNNING;
            if (run.status() != target) {
                run.setStatus(target);
            }
            if (!inProgress && !awaiting && !scheduler.allSucceeded(run)) {
                LOG.warn("run has no runnable stage runId={} stages={}", run.id(), run.stages().stream()
                        .map(s -> s.stageId() + "=" + s.status()).toList());
            }
        }
        boolean waiting = run.status() == RunStatus.WAITING_HUMAN || run.status() == RunStatus.SAFE_STOPPED;
        if (waiting && run.startedAt() != null) {
            run.openHumanWait(tx.now, run.status().name());
        } else if (!waiting) {
            run.closeHumanWait(tx.now);
        }
    }

    /**
     * Starts a new activation of a stage (PENDING/STALE or approved entry): records a checkpoint and submits
     * the first attempt.
     */
    private void startActivation(RunMutation tx, StageDefinition def, StageExecution exec) {
        Run run = tx.run;
        if (budgetExceeded(tx)) {
            if (exec.status().isAwaitingHuman()) {
                control.resetToReady(tx, exec, "budget exceeded before start", Actor.system());
            }
            return;
        }
        Checkpoint checkpoint = new Checkpoint(Ids.newId(), def.id(), run.planVersion(), run.acceptedVersions(),
                run.compensations().stream().map(Compensation::id).toList(), tx.now);
        run.addCheckpoint(checkpoint);
        tx.audit(AuditEventType.CHECKPOINT_CREATED, def.id(), Actor.system(), "checkpoint before " + def.id(),
                RunMutation.data("checkpointId", checkpoint.id(), "planVersion", checkpoint.planVersion(),
                        "acceptedVersions", checkpoint.acceptedVersions()));
        exec.setAttempts(0);
        exec.setActiveAgent(null);
        exec.setLastError(null);
        submitAttempt(tx, def, exec);
    }

    /**
     * Submits one attempt of a stage: checks budgets, selects the agent (primary, fault-injected primary or
     * deterministic fallback), records the attempt, builds the context and runs it on the stage executor with the
     * effective timeout ({@link EngineConfig#attemptTimeout(Duration, String)}: scaled for LLM provider agents),
     * which the STAGE_STARTED audit event records as {@code timeoutMs}.
     */
    private void submitAttempt(RunMutation tx, StageDefinition def, StageExecution exec) {
        Run run = tx.run;
        if (budgetExceeded(tx)) {
            if (exec.status().isAwaitingHuman()) {
                control.resetToReady(tx, exec, "budget exceeded before start", Actor.system());
            }
            return;
        }
        StageAgent agent;
        if (AgentRegistry.FALLBACK_AGENT_ID.equals(exec.activeAgent())) {
            agent = agents.deterministic(def.role());
        } else {
            agent = agents.agentFor(def.role(), run.options().providerId());
            exec.setActiveAgent(agent.id());
            FaultSpec fault = run.options().faultFor(def.id()).orElse(null);
            if (fault != null && run.injectedFaults(def.id()) < fault.failTimes()) {
                int injection = run.recordFaultInjection(def.id());
                agent = new FaultInjectingStageAgent(agent, fault.kind(), injection, fault.failTimes());
            }
        }
        String agentId = Objects.requireNonNull(exec.activeAgent());
        exec.setAttempts(exec.attempts() + 1);
        exec.setStatus(StageStatus.RUNNING);
        exec.markStarted(tx.now);
        exec.setPendingApproval(null);
        run.updateCounters(RunCounters::plusAgentCall);
        long token = tx.holder.nextToken();
        exec.setToken(token);
        StageContext context = stageExecutor.buildContext(run, def, exec);
        // LLM provider agents get the stage timeout times llmTimeoutScale (long streamed outputs)
        Duration timeout = config.attemptTimeout(def.timeout(), agentId);
        tx.audit(AuditEventType.STAGE_STARTED, def.id(), Actor.agent(agentId), "attempt " + exec.attempts() + " of "
                        + def.id() + " started with " + agentId,
                RunMutation.data("attempt", exec.attempts(), "maxAttempts", def.retryPolicy().maxAttempts(), "agent",
                        agentId, "planVersion", run.planVersion(), "derivedFrom", context.derivedFrom(),
                        "timeoutMs", timeout.toMillis()));
        String runId = run.id();
        String stageId = def.id();
        StageAgent chosen = agent;
        try {
            var future = executor.submit(() -> runAttempt(runId, stageId, token, chosen, agentId, context,
                    timeout));
            tx.holder.inFlight.put(stageId, new RunRegistry.InFlight(token, future));
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("engine is shutting down", e);
        }
        LOG.debug("stage started runId={} stageId={} attempt={} agent={}", runId, stageId, exec.attempts(), agentId);
    }

    /** Runs off the lock on a virtual thread; re-enters the lock to apply the outcome. */
    private void runAttempt(String runId, String stageId, long token, StageAgent agent, String agentId,
                            StageContext context, Duration timeout) {
        StageExecutor.AttemptOutcome outcome = stageExecutor.execute(agent, context, timeout);
        if (outcome == null || closed || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            mutate(runId, tx -> onAttemptFinished(tx, stageId, token, agentId, outcome));
        } catch (RuntimeException e) {
            if (closed) {
                LOG.debug("attempt result discarded after close runId={} stageId={}", runId, stageId);
                return;
            }
            LOG.error("applying attempt result failed runId={} stageId={}", runId, stageId, e);
            failAfterInternalError(runId, stageId, token, e);
        }
    }

    private void failAfterInternalError(String runId, String stageId, long token, RuntimeException error) {
        try {
            mutate(runId, tx -> {
                StageExecution exec = tx.run.stage(stageId);
                if (exec.status() != StageStatus.RUNNING || exec.token() != token || !tx.run.status().isActive()) {
                    return;
                }
                tx.holder.inFlight.remove(stageId);
                failStage(tx, exec, "internal engine error: " + error, HaltReason.AGENT_FAILURE_EXHAUSTED);
            });
        } catch (RuntimeException e) {
            LOG.error("could not safe-stop run after internal error runId={}", runId, e);
        }
    }

    private void onAttemptFinished(RunMutation tx, String stageId, long token, String agentId,
                                   StageExecutor.AttemptOutcome outcome) {
        Run run = tx.run;
        RunRegistry.InFlight inFlight = tx.holder.inFlight.get(stageId);
        if (inFlight == null || inFlight.token() != token) {
            return;
        }
        tx.holder.inFlight.remove(stageId);
        StageExecution exec = run.stage(stageId);
        if (exec.status() != StageStatus.RUNNING || exec.token() != token || !run.status().isActive()) {
            return;
        }
        StageDefinition def = graph.stage(stageId);
        exec.setEndedAt(tx.now);
        LlmTrace trace = switch (outcome.result()) {
            case StageResult.Success s -> s.llm();
            case StageResult.NeedsClarification c -> c.llm();
            case StageResult.Failure f -> f.failure().trace();
        };
        if (trace != null) {
            run.updateCounters(c -> c.plusLlmUsage(trace.inputTokens(), trace.outputTokens()));
        }
        String provider = trace != null ? trace.providerId() : providerOf(agentId);
        long in = trace != null ? trace.inputTokens() : 0L;
        long out = trace != null ? trace.outputTokens() : 0L;
        safeMetric(() -> metrics.agentCall(provider, def.role(), outcome.latencyMs(), in, out));
        switch (outcome.result()) {
            case StageResult.Success s -> handleSuccess(tx, def, exec, s, outcome, agentId);
            case StageResult.NeedsClarification c -> handleClarification(tx, exec, c, agentId);
            case StageResult.Failure f -> handleFailure(tx, def, exec, f.failure());
        }
        StageStatus status = exec.status();
        safeMetric(() -> metrics.stageCompleted(stageId, status, outcome.latencyMs()));
    }

    private void handleSuccess(RunMutation tx, StageDefinition def, StageExecution exec, StageResult.Success success,
                               StageExecutor.AttemptOutcome outcome, String agentId) {
        Run run = tx.run;
        ArtifactPayload payload = success.payload();
        try {
            if (payload.kind() != def.role().artifactKind()) {
                throw new StageFailure(FailureKind.INVALID_OUTPUT, "stage " + def.id() + " must produce "
                        + def.role().artifactKind() + " but produced " + payload.kind(), null, success.llm());
            }
            payload.validate();
        } catch (InvalidArtifactException e) {
            handleFailure(tx, def, exec, new StageFailure(FailureKind.INVALID_OUTPUT, e.getMessage(), e, success.llm()));
            return;
        } catch (StageFailure f) {
            handleFailure(tx, def, exec, f);
            return;
        }
        LlmTrace trace = success.llm();
        Provenance provenance = new Provenance(outcome.context().derivedFrom(), def.role(), agentId,
                trace != null ? trace.providerId() : providerOf(agentId), trace != null ? trace.model() : null,
                trace != null ? trace.promptHash() : null, trace != null ? trace.inputTokens() : 0L,
                trace != null ? trace.outputTokens() : 0L, outcome.latencyMs(), exec.attempts(), run.planVersion());
        Artifact previous = run.acceptedArtifact(def.id()).orElse(null);
        Artifact artifact = new Artifact(Ids.newId(), run.id(), def.id(), payload.kind(),
                run.nextArtifactVersion(def.id()), run.planVersion(), codec.hash(payload), payload, provenance,
                tx.now);
        run.addArtifact(artifact);
        tx.audit(AuditEventType.AGENT_DECISION, def.id(), Actor.agent(agentId), auditableRationale(success.rationale()),
                RunMutation.data("artifactId", artifact.id(), "version", artifact.version(), "kind", artifact.kind(),
                        "hash", artifact.hash(), "latencyMs", outcome.latencyMs(), "compensations",
                        success.compensations().size()));
        exec.setLastError(null);
        // The agent's side effects (for example a sandbox materialization) already happened, whatever the gate
        // decides: register their compensations now so that a denial or a later failure rolls them back.
        for (CompensationRequest request : success.compensations()) {
            run.addCompensation(new Compensation(Ids.newId(), def.id(), request.action(), request.target(),
                    request.description(), CompensationStatus.REGISTERED, tx.now, null, null));
        }

        // The exit gate runs before acceptance (on a prospective copy in which the artifact is accepted), for every
        // artifact including failed verification reports.
        GateEvaluator.GateDecision gate = gates.exit(run, def, artifact);
        recordFindings(tx, def.id(), GatePhase.EXIT, gate.findings());
        auditGate(tx, def.id(), GatePhase.EXIT, gate);
        if (gate.verdict() == Verdict.DENY) {
            // A denied artifact (for example one that embeds a credential) must never be persisted or shown: drop
            // it from the history (it was never accepted; its version number is not reused); the audit log keeps
            // the reference, hash and rule ids as evidence.
            run.removeArtifact(artifact.id());
            tx.audit(AuditEventType.POLICY_DENIED, def.id(), Actor.system(), "artifact " + artifact.reference()
                            + " denied and removed from history: " + gate.reasonText(),
                    RunMutation.data("phase", GatePhase.EXIT, "rules", gate.ruleIds(), "artifactId",
                            artifact.id(), "hash", artifact.hash(), "removed", true));
            failStage(tx, exec, "exit gate denied: " + gate.reasonText(), HaltReason.POLICY_DENIED);
            return;
        }
        if (payload instanceof VerificationReport report && !report.passed()) {
            handleVerificationFailure(tx, def, exec, report, artifact);
            return;
        }
        if (gate.verdict() == Verdict.REQUIRE_APPROVAL) {
            // Not accepted yet: the previously accepted version (if any) stays accepted until the approval.
            requestApproval(tx, exec, ApprovalKind.EXIT, gate, artifact.version(),
                    previous == null ? null : previous.version(), artifact.hash());
            return;
        }
        acceptArtifact(tx, def, artifact, null);
        markSucceeded(tx, exec);
    }

    /**
     * Accepts an artifact of the history for its stage (after an ALLOW exit gate or an approved exit gate): audits
     * ARTIFACT_ACCEPTED and, when it differs from the previously accepted version, invalidates the downstream
     * stages.
     */
    private void acceptArtifact(RunMutation tx, StageDefinition def, Artifact artifact, @Nullable String approvalId) {
        Run run = tx.run;
        Artifact previous = run.acceptedArtifact(def.id()).orElse(null);
        run.accept(def.id(), artifact.version());
        tx.audit(AuditEventType.ARTIFACT_ACCEPTED, def.id(), Actor.system(), artifact.reference() + " accepted"
                        + (approvalId == null ? "" : " after approval"),
                RunMutation.data("artifactId", artifact.id(), "version", artifact.version(), "hash", artifact.hash(),
                        "derivedFrom", artifact.provenance().derivedFrom(), "approvalId", approvalId,
                        "previousVersion", previous == null ? null : previous.version()));
        if (previous != null && !previous.hash().equals(artifact.hash())) {
            replanService.invalidateDownstream(tx, def.id(), false, "upstream artifact " + previous.reference()
                    + " was superseded by " + artifact.reference(), Actor.system());
        }
    }

    /** Returns the rationale to audit: abbreviated, and redacted when it contains a credential-like token. */
    private static String auditableRationale(@Nullable String rationale) {
        if (rationale == null) {
            return "";
        }
        return SecretPatterns.containsSecret(rationale) ? REDACTED_RATIONALE : abbreviate(rationale, 500);
    }

    /**
     * Verification feedback loop: the failed report is kept in the history (not accepted), its feedback is
     * appended to the implementation stage, which becomes STALE together with its downstream stages, and
     * verification waits for the re-implementation. Beyond the feedback loop budget the verification stage
     * fails (rollback, safe-stop AGENT_FAILURE_EXHAUSTED).
     */
    private void handleVerificationFailure(RunMutation tx, StageDefinition def, StageExecution exec,
                                           VerificationReport report, Artifact artifact) {
        Run run = tx.run;
        run.updateCounters(RunCounters::plusFeedbackLoop);
        int loops = run.counters().feedbackLoops();
        int max = run.budget().maxFeedbackLoops();
        if (loops > max) {
            failStage(tx, exec, "verification kept failing after " + max + " feedback loops",
                    HaltReason.AGENT_FAILURE_EXHAUSTED);
            return;
        }
        String target = graph.upstreamOf(def.id()).stream()
                .filter(id -> graph.stage(id).role() == AgentRole.IMPLEMENTATION)
                .reduce((first, second) -> second).orElse(null);
        if (target == null) {
            failStage(tx, exec, "verification failed and no implementation stage can receive feedback",
                    HaltReason.AGENT_FAILURE_EXHAUSTED);
            return;
        }
        StageExecution implementation = run.stage(target);
        for (String feedback : report.feedbackForImplementation()) {
            implementation.addFeedback("[" + artifact.reference() + "] " + feedback);
        }
        tx.audit(AuditEventType.FEEDBACK_LOOP, def.id(), Actor.system(), "verification failed; feedback loop " + loops
                        + "/" + max + " routes " + report.feedbackForImplementation().size() + " findings to " + target,
                RunMutation.data("loop", loops, "max", max, "from", artifact.reference(), "to", target, "feedback",
                        report.feedbackForImplementation()));
        exec.setLastError("verification failed (" + artifact.reference() + "); feedback sent to " + target);
        control.resetToReady(tx, exec, "verification feedback loop " + loops, Actor.system());
        replanService.invalidateDownstream(tx, target, true, "verification feedback loop " + loops, Actor.system());
    }

    private void handleClarification(RunMutation tx, StageExecution exec, StageResult.NeedsClarification request,
                                     String agentId) {
        Run run = tx.run;
        exec.setStatus(StageStatus.AWAITING_CLARIFICATION);
        run.setClarification(new Clarification(exec.stageId(), request.questions(), Map.of(), tx.now, null, null));
        tx.audit(AuditEventType.AGENT_DECISION, exec.stageId(), Actor.agent(agentId),
                auditableRationale(request.rationale()), RunMutation.data("needsClarification", true));
        tx.audit(AuditEventType.CLARIFICATION_REQUESTED, exec.stageId(), Actor.system(),
                request.questions().size() + " clarification questions asked",
                RunMutation.data("questionIds", request.questions().stream().map(ClarificationQuestion::id).toList()));
    }

    /**
     * Retry (retryable failure with attempts left), else fallback to the deterministic agent (when enabled and
     * not already engaged), else stage failure with rollback and safe-stop.
     */
    private void handleFailure(RunMutation tx, StageDefinition def, StageExecution exec, StageFailure failure) {
        Run run = tx.run;
        exec.setLastError(failure.describe());
        run.markFailure(tx.now, def.id());
        if (failure.retryable() && exec.attempts() < def.retryPolicy().maxAttempts()) {
            Duration backoff = def.retryPolicy().backoff(exec.attempts(), config.maxBackoff(), config.backoffScale());
            exec.setStatus(StageStatus.RETRY_SCHEDULED);
            exec.setNextAttemptAt(tx.now.plus(backoff));
            long token = tx.holder.nextToken();
            exec.setToken(token);
            run.updateCounters(RunCounters::plusRetry);
            safeMetric(() -> metrics.retry(def.id()));
            tx.audit(AuditEventType.RETRY_SCHEDULED, def.id(), Actor.system(), "attempt " + exec.attempts()
                            + " failed (" + failure.describe() + "); retry in " + backoff.toMillis() + " ms",
                    RunMutation.data("attempt", exec.attempts(), "kind", failure.kind(), "error", failure.getMessage(),
                            "backoffMs", backoff.toMillis(), "nextAttemptAt", exec.nextAttemptAt()));
            scheduleRetry(run.id(), def.id(), token, backoff);
            return;
        }
        if (def.fallbackToDeterministic() && !AgentRegistry.FALLBACK_AGENT_ID.equals(exec.activeAgent())
                && agents.hasDeterministic(def.role())) {
            String from = exec.activeAgent();
            exec.setActiveAgent(AgentRegistry.FALLBACK_AGENT_ID);
            exec.setAttempts(0);
            run.updateCounters(RunCounters::plusFallback);
            safeMetric(() -> metrics.fallback(def.id()));
            tx.audit(AuditEventType.FALLBACK_ENGAGED, def.id(), Actor.system(), "primary agent " + from
                            + " exhausted (" + failure.describe() + "); deterministic fallback engaged",
                    RunMutation.data("from", from, "to", AgentRegistry.FALLBACK_AGENT_ID, "kind", failure.kind(),
                            "error", failure.getMessage()));
            submitAttempt(tx, def, exec);
            return;
        }
        failStage(tx, exec, (failure.retryable() ? "attempts exhausted: " : "non-retryable failure: ")
                + failure.describe(), HaltReason.AGENT_FAILURE_EXHAUSTED);
    }

    private void failStage(RunMutation tx, StageExecution exec, String error, HaltReason haltReason) {
        exec.setStatus(StageStatus.FAILED);
        exec.setLastError(error);
        exec.setPendingApproval(null);
        if (exec.endedAt() == null) {
            exec.setEndedAt(tx.now);
        }
        tx.audit(AuditEventType.STAGE_FAILED, exec.stageId(), Actor.system(), "stage " + exec.stageId() + " failed: "
                + error, RunMutation.data("error", error, "haltReason", haltReason));
        LOG.warn("stage failed runId={} stageId={} error={}", tx.run.id(), exec.stageId(), error);
        rollbackService.rollback(tx, exec.stageId(), error, haltReason);
    }

    private void markSucceeded(RunMutation tx, StageExecution exec) {
        exec.setStatus(StageStatus.SUCCEEDED);
        exec.setPendingApproval(null);
        tx.run.markRecoveredIfFirstFailure(tx.now, exec.stageId());
        tx.audit(AuditEventType.STAGE_SUCCEEDED, exec.stageId(), Actor.system(), "stage " + exec.stageId()
                + " succeeded", RunMutation.data("attempts", exec.attempts(), "agent", exec.activeAgent(), "version",
                tx.run.acceptedVersions().get(exec.stageId())));
    }

    /**
     * Requests a human approval. The recorded risk level is the risk the gate was evaluated with (for an exit gate
     * the risk the run will have once the artifact under review is accepted).
     */
    private void requestApproval(RunMutation tx, StageExecution exec, ApprovalKind kind,
                                 GateEvaluator.GateDecision gate, @Nullable Integer artifactVersion,
                                 @Nullable Integer previousAcceptedVersion, @Nullable String artifactHash) {
        Run run = tx.run;
        Approval approval = Approval.request(exec.stageId(), kind, gate.reasonText(), gate.ruleIds(),
                gate.risk().level(), run.planVersion(), artifactVersion, previousAcceptedVersion, artifactHash,
                tx.now);
        run.addApproval(approval);
        exec.setPendingApproval(approval);
        exec.setStatus(StageStatus.AWAITING_APPROVAL);
        run.updateCounters(RunCounters::plusApprovalRequested);
        tx.audit(AuditEventType.APPROVAL_REQUESTED, exec.stageId(), Actor.system(), kind + " approval requested for "
                        + exec.stageId() + ": " + gate.reasonText(),
                RunMutation.data("approvalId", approval.id(), "kind", kind, "reason", gate.reasonText(), "ruleIds",
                        gate.ruleIds(), "riskLevel", approval.riskLevel(), "artifactVersion", artifactVersion,
                        "artifactHash", artifactHash, "previousAcceptedVersion", previousAcceptedVersion));
    }

    private void abortRun(RunMutation tx, Actor actor, String detail) {
        Run run = tx.run;
        control.cancelAllInFlight(tx, "run aborted", actor);
        for (Approval approval : List.copyOf(run.approvals())) {
            if (approval.status() == ApprovalStatus.PENDING) {
                control.revoke(tx, approval, "run aborted", actor);
            }
        }
        List<String> cancelled = new ArrayList<>();
        for (StageExecution exec : run.stages()) {
            StageExecution live = run.stage(exec.stageId());
            if (live.status() != StageStatus.SUCCEEDED && live.status() != StageStatus.FAILED) {
                live.setStatus(StageStatus.CANCELLED);
                live.setPendingApproval(null);
                cancelled.add(live.stageId());
            }
        }
        List<Compensation> registered = run.compensations().stream()
                .filter(c -> c.status() == CompensationStatus.REGISTERED).toList();
        rollbackService.compensate(tx, registered.reversed(), "abort");
        run.setStatus(RunStatus.ABORTED);
        run.setHalt(null, detail);
        run.setEndedAt(tx.now);
        run.closeHumanWait(tx.now);
        tx.audit(AuditEventType.RUN_ABORTED, null, actor, "run aborted by " + actor.id() + ": " + detail,
                RunMutation.data("cancelledStages", cancelled, "compensations", registered.size()));
        LOG.info("run aborted runId={} by={}", run.id(), actor.id());
    }

    /**
     * Completes the run: finalizes the release change record with the authoritative human approvals (as a new
     * engine-produced artifact version), sets COMPLETED and records run metrics.
     */
    private void completeRun(RunMutation tx) {
        Run run = tx.run;
        for (StageDefinition def : graph.stages()) {
            Artifact accepted = run.acceptedArtifact(def.id()).orElse(null);
            if (accepted != null && accepted.payload() instanceof ReleaseReadiness release) {
                List<ReleaseReadiness.ApprovalRecord> records = run.approvals().stream()
                        .filter(a -> a.status() == ApprovalStatus.APPROVED && a.planVersion() == run.planVersion())
                        .filter(a -> a.decidedBy() != null && a.decidedAt() != null)
                        .map(a -> new ReleaseReadiness.ApprovalRecord(a.stageId(), a.decidedBy(),
                                a.decidedAt().toString(), a.comment() == null ? "" : a.comment(),
                                a.artifactHash() == null ? "" : a.artifactHash()))
                        .toList();
                List<String> releaseApprovers = records.stream().filter(r -> r.stageId().equals(def.id()))
                        .map(ReleaseReadiness.ApprovalRecord::approver).distinct().toList();
                String verdict = "REQUIRE_APPROVAL satisfied: release approved by "
                        + (releaseApprovers.isEmpty() ? "-" : String.join(", ", releaseApprovers))
                        + (release.decision() == ReleaseReadiness.GoNoGo.NO_GO ? " with waiver" : "");
                List<String> artifactIds = run.acceptedArtifacts().stream().filter(a -> !a.stageId().equals(def.id()))
                        .map(Artifact::id).toList();
                ReleaseReadiness.ChangeRecord record = release.changeRecord();
                ReleaseReadiness finalized = release.withChangeRecord(new ReleaseReadiness.ChangeRecord(
                        record.summary(), record.riskLevel(), record.rollbackPlan(), records, artifactIds, verdict));
                Map<String, Integer> derivedFrom = new LinkedHashMap<>(accepted.provenance().derivedFrom());
                derivedFrom.put(def.id(), accepted.version());
                Provenance provenance = new Provenance(derivedFrom, def.role(), ENGINE_AGENT_ID, ENGINE_AGENT_ID, null,
                        null, 0L, 0L, 0L, 0, run.planVersion());
                Artifact artifact = new Artifact(Ids.newId(), run.id(), def.id(), finalized.kind(),
                        run.nextArtifactVersion(def.id()), run.planVersion(), codec.hash(finalized), finalized,
                        provenance, tx.now);
                run.addArtifact(artifact);
                run.accept(def.id(), artifact.version());
                tx.audit(AuditEventType.ARTIFACT_ACCEPTED, def.id(), Actor.system(), artifact.reference()
                                + " accepted: change record finalized with " + records.size() + " approvals",
                        RunMutation.data("artifactId", artifact.id(), "version", artifact.version(), "hash",
                                artifact.hash(), "approvals", records.size()));
            }
        }
        run.setStatus(RunStatus.COMPLETED);
        run.setEndedAt(tx.now);
        run.closeHumanWait(tx.now);
        long endToEnd = run.endToEndMs() == null ? 0L : run.endToEndMs();
        long active = run.activeMs(tx.now);
        tx.audit(AuditEventType.RUN_COMPLETED, null, Actor.system(), "run completed",
                RunMutation.data("endToEndMs", endToEnd, "activeMs", active, "humanWaitMs", run.humanWaitMs(tx.now),
                        "planVersion", run.planVersion()));
        safeMetric(() -> metrics.runCompleted(endToEnd, active));
        LOG.info("run completed runId={} endToEndMs={} activeMs={}", run.id(), endToEnd, active);
    }

    /**
     * Checks the agent call, token and active time budgets before a submission; on breach audits
     * BUDGET_EXCEEDED and safe-stops the run with BUDGET_EXCEEDED. The check uses the counters at submission
     * time: tokens and time of attempts of parallel branches that are still running are counted only when they
     * finish, so a run may overshoot those budgets by what up to the number of concurrent branches consume.
     */
    private boolean budgetExceeded(RunMutation tx) {
        Run run = tx.run;
        RunBudget budget = run.budget();
        RunCounters counters = run.counters();
        String name = null;
        String detail = null;
        if (counters.agentCalls() >= budget.maxAgentCalls()) {
            name = "maxAgentCalls";
            detail = "agent call budget of " + budget.maxAgentCalls() + " reached";
        } else if (counters.llmTokens() > 0 && counters.llmTokens() >= budget.maxLlmTokens()) {
            name = "maxLlmTokens";
            detail = "LLM token budget of " + budget.maxLlmTokens() + " reached (" + counters.llmTokens() + " used)";
        } else if (run.activeMs(tx.now) >= budget.maxActiveDuration().toMillis()) {
            name = "maxActiveDuration";
            detail = "active duration budget of " + budget.maxActiveDuration() + " reached";
        }
        if (name == null) {
            return false;
        }
        tx.audit(AuditEventType.BUDGET_EXCEEDED, null, Actor.system(), detail,
                RunMutation.data("budget", name, "agentCalls", counters.agentCalls(), "llmTokens", counters.llmTokens(),
                        "activeMs", run.activeMs(tx.now)));
        control.safeStop(tx, HaltReason.BUDGET_EXCEEDED, detail, Actor.system());
        return true;
    }

    private void scheduleRetry(String runId, String stageId, long token, Duration delay) {
        scheduleWakeUp(runId, stageId, token, delay, 0);
    }

    /**
     * Arms the wake-up of a scheduled retry. The single scheduler thread only hands the wake-up to the stage
     * executor, so run mutations (and their store I/O) never run on, or block, the scheduler thread.
     */
    private void scheduleWakeUp(String runId, String stageId, long token, Duration delay, int failedWakeUps) {
        try {
            retryScheduler.schedule(() -> handOffWakeUp(runId, stageId, token, delay, failedWakeUps),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!closed) {
                throw new IllegalStateException("retry scheduler rejected the retry", e);
            }
        }
    }

    private void handOffWakeUp(String runId, String stageId, long token, Duration delay, int failedWakeUps) {
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> onRetryDue(runId, stageId, token, delay, failedWakeUps));
        } catch (RejectedExecutionException e) {
            if (!closed) {
                onWakeUpFailed(runId, stageId, token, delay, failedWakeUps, e);
            }
        }
    }

    private void onRetryDue(String runId, String stageId, long token, Duration delay, int failedWakeUps) {
        if (closed) {
            return;
        }
        try {
            mutate(runId, tx -> {
                StageExecution exec = tx.run.stage(stageId);
                if (!isWakeUpTarget(tx.run, exec, token)) {
                    return;
                }
                submitAttempt(tx, graph.stage(stageId), exec);
            });
        } catch (RuntimeException e) {
            if (!closed) {
                onWakeUpFailed(runId, stageId, token, delay, failedWakeUps, e);
            }
        }
    }

    /**
     * A failed wake-up is retried with the same delay up to {@link #MAX_WAKE_UP_RETRIES} times (each retry is a
     * no-op when the stage is no longer waiting for this retry); after that the stage fails with
     * AGENT_FAILURE_EXHAUSTED (rollback, safe-stop) instead of staying RETRY_SCHEDULED forever.
     */
    private void onWakeUpFailed(String runId, String stageId, long token, Duration delay, int failedWakeUps,
                                RuntimeException error) {
        if (failedWakeUps < MAX_WAKE_UP_RETRIES) {
            LOG.warn("retry wake-up failed runId={} stageId={} failure={}/{} error={}; retrying in {} ms", runId,
                    stageId, failedWakeUps + 1, MAX_WAKE_UP_RETRIES + 1, error.toString(), delay.toMillis());
            try {
                scheduleWakeUp(runId, stageId, token, delay, failedWakeUps + 1);
                return;
            } catch (RuntimeException e) {
                LOG.error("could not re-arm the retry wake-up runId={} stageId={}", runId, stageId, e);
            }
        }
        LOG.error("retry wake-up failed {} times; failing the stage runId={} stageId={}", failedWakeUps + 1, runId,
                stageId, error);
        try {
            mutate(runId, tx -> {
                StageExecution exec = tx.run.stage(stageId);
                if (!isWakeUpTarget(tx.run, exec, token)) {
                    return;
                }
                failStage(tx, exec, "retry wake-up failed " + (failedWakeUps + 1) + " times: " + error,
                        HaltReason.AGENT_FAILURE_EXHAUSTED);
            });
        } catch (RuntimeException e) {
            if (!closed) {
                LOG.error("could not fail the stage after failed retry wake-ups runId={} stageId={}", runId, stageId,
                        e);
            }
        }
    }

    private static boolean isWakeUpTarget(Run run, StageExecution exec, long token) {
        return exec.status() == StageStatus.RETRY_SCHEDULED && exec.token() == token && run.status().isActive();
    }

    // =========================================================================================== helpers

    private void recordFindings(RunMutation tx, @Nullable String stageId, GatePhase phase,
                                List<PolicyFinding> findings) {
        for (PolicyFinding finding : findings) {
            tx.run.addPolicyFinding(new PolicyFindingRecord(finding.ruleId(), finding.severity(),
                    finding.verdict().name(), finding.message(), stageId, phase.name(), tx.run.planVersion(), tx.now));
            tx.audit(AuditEventType.POLICY_FINDING, stageId, Actor.system(), finding.ruleId() + " " + finding.verdict()
                            + ": " + finding.message(),
                    RunMutation.data("ruleId", finding.ruleId(), "severity", finding.severity(), "verdict",
                            finding.verdict(), "phase", phase));
        }
    }

    private static void auditGate(RunMutation tx, String stageId, GatePhase phase, GateEvaluator.GateDecision gate) {
        tx.audit(AuditEventType.GATE_EVALUATED, stageId, Actor.system(), phase + " gate of " + stageId + ": "
                        + gate.verdict() + (gate.reasons().isEmpty() ? "" : " (" + gate.reasonText() + ")"),
                RunMutation.data("phase", phase, "verdict", gate.verdict(), "reasons", gate.reasons(), "notes",
                        gate.notes(), "ruleIds", gate.ruleIds()));
    }

    private StageDefinition requireStage(String stageId) {
        if (stageId == null || !graph.contains(stageId)) {
            throw new InvalidCommandException("unknown stage: " + stageId);
        }
        return graph.stage(stageId);
    }

    private static Approval requirePendingApproval(StageExecution exec, @Nullable String expectedApprovalId) {
        Approval pending = exec.pendingApproval();
        if (exec.status() != StageStatus.AWAITING_APPROVAL || pending == null
                || pending.status() != ApprovalStatus.PENDING) {
            throw new InvalidRunStateException("stage " + exec.stageId() + " is not awaiting approval (status "
                    + exec.status() + ")");
        }
        if (expectedApprovalId != null && !expectedApprovalId.equals(pending.id())) {
            throw new InvalidRunStateException("approval " + expectedApprovalId + " is no longer pending; the stage "
                    + "now awaits approval " + pending.id());
        }
        return pending;
    }

    private static void requireActive(Run run) {
        if (!run.status().isActive()) {
            throw new InvalidRunStateException("run is " + run.status()
                    + (run.status() == RunStatus.SAFE_STOPPED ? "; resume or re-plan first" : ""));
        }
    }

    private static void requireHuman(Actor actor, String what) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.HUMAN) {
            throw new InvalidCommandException(what + " require a human operator identity");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("orchestration engine is closed");
        }
    }

    private Instant now() {
        return Clocks.now(config.clock());
    }

    private static String providerOf(String agentId) {
        if (AgentRegistry.FALLBACK_AGENT_ID.equals(agentId)) {
            return AgentRegistry.DETERMINISTIC;
        }
        int colon = agentId.indexOf(':');
        return colon > 0 ? agentId.substring(0, colon) : agentId;
    }

    private static String providerLabel(@Nullable String providerId) {
        return providerId == null || providerId.isBlank() ? AgentRegistry.DETERMINISTIC : providerId;
    }

    private static List<String> ruleIds(List<PolicyFinding> findings) {
        return findings.stream().filter(f -> f.verdict() == Verdict.DENY).map(PolicyFinding::ruleId).toList();
    }

    private static String denialDetail(List<PolicyFinding> findings) {
        return findings.stream().filter(f -> f.verdict() == Verdict.DENY)
                .map(f -> f.ruleId() + ": " + f.message()).collect(Collectors.joining("; "));
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static void safeMetric(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.warn("metrics recorder failed error={}", e.toString());
        }
    }
}
