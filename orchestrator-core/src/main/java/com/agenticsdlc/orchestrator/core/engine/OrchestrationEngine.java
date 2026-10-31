package com.agenticsdlc.orchestrator.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.ActorType;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.UnsignedChangeRecordSigner;
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
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyDecision;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.rules.NoGoReleaseRule;
import com.agenticsdlc.orchestrator.core.port.RunLease;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;
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
 * safe-stop the run. An engine-wide circuit breaker per LLM provider ({@link EngineConfig.ProviderCircuitBreaker})
 * routes new attempts to the deterministic agents while a provider keeps failing ({@link #providerCircuits()}). A failed verification feeds back to implementation (bounded loop). Humans approve, reject,
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
 * it to persist both atomically) while still holding the lock, right after the run's lease was renewed: a node whose
 * lease was taken over never commits. When the commit fails, the caller gets an {@link InvalidRunStateException}
 * (a conflict, with the store exception as its cause; the store's own exception never escapes) and the mutation is
 * discarded: the run is dropped from the in-memory registry (its in-flight attempts are abandoned and its lease is
 * released), so the next command or read loads the stored state again. A failed {@code start} unregisters
 * the new run, so no run that was never persisted stays visible. {@link #get} and {@link #list} return copies built
 * under the lock.</p>
 *
 * <p>Lost leases: when this node finds that it lost the lease of a run it was executing (an attempt heartbeat whose
 * renewal fails, a renewal at the start of a mutation, or the renewal right before a commit), it writes nothing to the
 * store, drops the lease, abandons the run's in-flight attempts (an attempt that finds out through its heartbeat is
 * interrupted with a LEASE_LOST outcome, treated like an interruption: nothing is applied or retried on this node),
 * evicts the run from the registry and logs a WARN; a command that ran into it gets a
 * {@link RunLeasedElsewhereException}.</p>
 *
 * <p>Audit hash chain: every audit event is linked to the run's previous event ({@code prevHash}, {@code hash}; see
 * {@link com.agenticsdlc.orchestrator.core.audit.AuditChain}) when it is created; the chain head is part of the
 * run's snapshot and is resynchronized with {@link AuditSink#lastEvent} when a run is loaded from the store (also
 * right after a failed commit, which drops the in-memory copy), so the chain continues across restarts, nodes and
 * failed commits without gaps.</p>
 *
 * <p>Failure texts: agent and provider error messages may echo credentials or whole request and response bodies, so
 * the engine records them (stage last error, RETRY_SCHEDULED, FALLBACK_ENGAGED and STAGE_FAILED audit events, logs,
 * circuit snapshots) only with credential-like tokens redacted and at most 1000 characters.</p>
 *
 * <p>Signed change records: when the run completes, the release change record is finalized with the approvals and
 * signed by {@link EngineConfig#changeRecordSigner()} (unsigned by default); the ARTIFACT_ACCEPTED event of the
 * finalized record names the signature algorithm and key id.</p>
 *
 * <p>Multi-node operation: a run is leased ({@link RunLeaseProvider}) by the node that executes its stages, from
 * the command or event that schedules work until the run waits for a human, is safe-stopped or ends. The lease is
 * renewed at every mutation and periodically while an attempt runs. A mutation of a run leased by another node
 * throws {@link RunLeasedElsewhereException}; reads never need a lease. When this node acquires a run's lease it
 * first reloads the run if the store holds a newer state (written by another node), and reads of runs that are not
 * executing here are refreshed from the store the same way. Within one node, the per-run lock remains the
 * mechanism. When releasing a lease fails, the node keeps it as a pending release: its own next command on the run
 * renews and uses it, the run is not evicted, and the release is retried by {@link #releasePendingLeases()} (which
 * {@link #recoverInterruptedCount()} calls) and once more on close. A node is never refused with its own node id:
 * when the provider names this node as the holder of a lease it does not hold locally (a restart with a fixed node
 * id), the lease is reclaimed ({@link RunLeaseProvider#reclaim}).</p>
 *
 * <p>Bounded memory: runs that are not executing on this node are evicted from the in-memory registry after
 * {@link EngineConfig#registryRetention()} without a change, and terminal runs beyond
 * {@link EngineConfig#registryMaxTerminalRuns()} are evicted least recently ended first ({@link #evictIdleRuns()}).
 * {@link #get} and {@link #list} fall back to the store for runs that are not cached, and a command on such a run
 * loads it again first.</p>
 *
 * <p>Commands throw {@link RunNotFoundException} for unknown runs, {@link InvalidRunStateException} (an
 * {@link IllegalStateException}) when the run or stage is in the wrong state, its subclass
 * {@link RunLeasedElsewhereException} when another node is executing the run, {@link InvalidCommandException}
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

    /** Maximum length of a failure text recorded in the run, the audit trail and circuit snapshots. */
    static final int MAX_FAILURE_TEXT = 1000;

    /** Maximum impacted source paths recorded in STAGE_STARTED data. */
    static final int MAX_AUDITED_SOURCES = 40;

    /** Maximum impacted source notes recorded in STAGE_STARTED data. */
    static final int MAX_AUDITED_SOURCE_NOTES = 10;

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
    private final ProviderCircuits circuits;
    private final RunLeaseProvider leases;
    private final String nodeId;
    private final @Nullable PolicyPack policyPack;
    /** Audit data {@code policyPack} (name, version, hash) of the active pack, or null without a pack. */
    private final @Nullable Map<String, Object> policyPackData;
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
        this.leases = config.leaseProvider();
        this.nodeId = config.nodeId();
        this.registry = new RunRegistry(this::loadForRegistry);
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
        this.circuits = new ProviderCircuits(config.providerCircuitBreaker());
        this.policyPack = policyEngine.pack();
        this.policyPackData = policyPack == null ? null : RunMutation.data("name", policyPack.name(), "version",
                policyPack.version(), "hash", policyEngine.packHash());
    }

    /**
     * Returns the policy pack of this engine's policy engine. When a pack is active, its name, version and hash are
     * recorded as data {@code policyPack} of every RUN_CREATED and POLICY_DENIED audit event.
     *
     * @return the active pack, or null when only the built-in rules are active
     */
    public @Nullable PolicyPack policyPack() {
        return policyPack;
    }

    /**
     * Returns the workflow graph.
     *
     * @return the graph
     */
    public WorkflowGraph workflowGraph() {
        return graph;
    }

    /**
     * Returns the circuit breaker state of every registered LLM provider (and of any other provider with recorded
     * failures): state (CLOSED, OPEN, HALF_OPEN), consecutive provider-attributable failures, when the circuit
     * opened and when it lets the next attempt try the provider again. The circuits are engine-wide (shared by all
     * runs) and live in memory only; a restart closes them.
     *
     * @return provider id to circuit state, sorted by provider id (an unmodifiable snapshot)
     */
    public Map<String, ProviderCircuit> providerCircuits() {
        Set<String> ids = new TreeSet<>(agents.providerIds());
        ids.remove(AgentRegistry.DETERMINISTIC);
        return circuits.snapshot(ids, now());
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
        registry.register(run).committed = false;
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
                    removed.discarded = true;
                    removed.inFlight.values().forEach(f -> f.future().cancel(true));
                    removed.inFlight.clear();
                    RunLease lease = removed.lease;
                    removed.lease = null;
                    removed.releasePending = false;
                    if (lease != null) {
                        tryRelease(lease);
                    }
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
                            "requestedBy", requestedBy.id(), "nodeId", tx.nodeId,
                            "requirementChars", run.requirement().length(), "faults",
                            options.faults().stream().map(f -> f.stageId() + ":" + f.kind() + "x" + f.failTimes())
                                    .toList(), "policyPack", policyPackData));
            PolicyDecision decision = gates.runStart(run, run.activeRequirement());
            recordFindings(tx, null, GatePhase.RUN_START, decision.findings());
            if (decision.verdict() == Verdict.DENY) {
                String detail = denialDetail(decision.findings());
                tx.audit(AuditEventType.POLICY_DENIED, null, Actor.system(), "requirement denied by policy: " + detail,
                        RunMutation.data("phase", GatePhase.RUN_START, "rules", ruleIds(decision.findings()),
                                "policyPack", policyPackData));
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
                                "requirementChars", requirement.length(), "policyPack", policyPackData));
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
                            budgetExtended, "budget", run.budget(), "nodeId", tx.nodeId));
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
     * Returns a snapshot copy of a run, safe to read and serialize. A run that is cached in memory is returned from
     * memory, refreshed first from the store when the store holds a newer state written by another node (only for
     * runs that are not executing on this node and not terminal); a run that is not cached (evicted, or never seen
     * by this engine) is read from the store without caching it. Reads never need the run's lease.
     *
     * @param runId run id
     * @return the copy
     * @throws RunNotFoundException when unknown
     */
    public Run get(String runId) {
        RunRegistry.RunHolder holder = registry.find(runId);
        if (holder != null) {
            holder.lock.lock();
            try {
                if (!holder.evicted && !holder.discarded) {
                    if ((holder.lease == null || holder.releasePending) && holder.committed
                            && !holder.run.status().isTerminal()) {
                        refreshIfStale(holder);
                    }
                    return holder.run.copy();
                }
            } finally {
                holder.lock.unlock();
            }
        }
        return runStore.load(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    /**
     * Returns snapshot copies of all runs, newest first: the runs cached in memory merged with every run of the
     * store ({@link RunStore#loadAll()}), without duplicates. The cached copy of a run wins, unless this node is not
     * executing the run and the store holds a newer state (written by another node).
     *
     * <p>This is the authoritative listing, but it is expensive: every call loads every run of the store (with a JPA
     * store, every run aggregate is read and deserialized). Use {@link #listCached()} for frequent, cheap reads that
     * may omit runs, and this method where completeness matters (metrics over all runs, startup reports).</p>
     *
     * @return copies
     */
    public List<Run> list() {
        Map<String, Run> byId = new HashMap<>();
        for (Run stored : runStore.loadAll()) {
            byId.put(stored.id(), stored);
        }
        for (RunRegistry.RunHolder holder : registry.all()) {
            holder.lock.lock();
            try {
                if (holder.evicted || holder.discarded) {
                    continue;
                }
                Run live = holder.run;
                Run stored = byId.get(live.id());
                boolean executingHere = holder.lease != null && !holder.releasePending;
                if (stored == null || executingHere || !newer(stored, live)) {
                    byId.put(live.id(), live.copy());
                }
            } finally {
                holder.lock.unlock();
            }
        }
        List<Run> runs = new ArrayList<>(byId.values());
        runs.sort(Comparator.comparing(Run::createdAt).reversed().thenComparing(Run::id));
        evictQuietly(null);
        return List.copyOf(runs);
    }

    /**
     * Returns snapshot copies of the runs cached in this engine's in-memory registry only, newest first. Cheap: it
     * never touches the store. Not authoritative: runs evicted from memory ({@link #evictIdleRuns()}), runs that
     * this engine never loaded (for example created by another node, or persisted before a restart and not yet read)
     * are missing, and a cached run that another node changed meanwhile may be stale. Use {@link #list()} when every
     * run must be included.
     *
     * @return copies of the cached runs
     */
    public List<Run> listCached() {
        List<Run> runs = new ArrayList<>();
        for (RunRegistry.RunHolder holder : registry.all()) {
            holder.lock.lock();
            try {
                if (!holder.evicted && !holder.discarded) {
                    runs.add(holder.run.copy());
                }
            } finally {
                holder.lock.unlock();
            }
        }
        runs.sort(Comparator.comparing(Run::createdAt).reversed().thenComparing(Run::id));
        return List.copyOf(runs);
    }

    /**
     * Evicts runs that are not executing on this node from the in-memory registry: runs without a change for
     * {@link EngineConfig#registryRetention()}, then the least recently ended terminal runs beyond
     * {@link EngineConfig#registryMaxTerminalRuns()}. A run is never evicted while it is RUNNING, has an in-flight
     * attempt, is leased by this node or has a pending lease release. Evicted runs remain readable
     * through {@link #get} and {@link #list} (from the store) and are loaded again by the next command. The engine
     * calls this after every command and in {@link #list()}; adapters may also call it periodically.
     *
     * @return the number of evicted runs
     */
    public int evictIdleRuns() {
        return evictAt(now());
    }

    private int evictAt(Instant at) {
        List<String> evicted = registry.evictIdle(at, config.registryRetention(), config.registryMaxTerminalRuns());
        if (!evicted.isEmpty()) {
            LOG.debug("evicted idle runs from memory count={} cached={}", evicted.size(), registry.size());
        }
        return evicted.size();
    }

    /** Evicts idle runs at the given time (or now), never failing the caller. */
    private void evictQuietly(@Nullable Instant at) {
        try {
            evictAt(at != null ? at : now());
        } catch (RuntimeException e) {
            LOG.warn("run eviction failed error={}", e.toString());
        }
    }

    /**
     * Returns whether this engine currently caches the run in memory (diagnostics and tests).
     *
     * @param runId run id
     * @return true when the run is registered
     */
    boolean isCached(String runId) {
        return registry.find(runId) != null;
    }

    /**
     * Recovers runs interrupted by a restart: every persisted RUNNING run that no node holds a valid lease on (it is
     * not executing anywhere) gets its RUNNING stages reset to PENDING (STALE when an accepted artifact exists) and
     * is SAFE_STOPPED with {@link HaltReason#INTERRUPTED_BY_RESTART} (audited RUN_INTERRUPTED and SAFE_STOP). Runs
     * leased by a node are skipped (another node is executing them); runs waiting for a human, stopped or terminal
     * are left in the store and loaded on demand. Call once at startup; it is safe to call again later (for example
     * periodically, to recover runs of a node that died once its lease expired). Same as
     * {@link #recoverInterruptedCount()} without the count.
     */
    public void recoverInterrupted() {
        recoverInterruptedCount();
    }

    /**
     * Recovers runs interrupted by a restart exactly like {@link #recoverInterrupted()} and returns how many were
     * recovered. The summary is logged at INFO when runs were recovered and at DEBUG otherwise, so that periodic
     * maintenance passes that find nothing to do stay quiet (runs skipped because a node executes them are logged at
     * DEBUG for the same reason).
     *
     * <p>It first retries the releases of leases kept after a failed release ({@link #releasePendingLeases()}), so
     * that a periodic maintenance pass also cleans those up. A RUNNING run whose valid lease names this node while
     * this engine holds no lease for it (a restart with a fixed node id) is recovered too: its lease is reclaimed
     * ({@link RunLeaseProvider#reclaim}); when the provider cannot reclaim, the run is skipped until the lease
     * expires.</p>
     *
     * @return the number of runs that were safe-stopped with {@link HaltReason#INTERRUPTED_BY_RESTART} by this call
     * @throws IllegalStateException when the engine is closed
     */
    public int recoverInterruptedCount() {
        ensureOpen();
        releasePendingLeases();
        int recovered = 0;
        for (Run loaded : runStore.loadAll()) {
            if (loaded.status() != RunStatus.RUNNING) {
                continue;
            }
            Optional<String> owner = leases.holderOf(loaded.id());
            if (owner.isPresent() && !(nodeId.equals(owner.get()) && !holdsLeaseLocally(loaded.id()))) {
                LOG.debug("run not recovered: it is executing on node {} runId={}", owner.get(), loaded.id());
                continue;
            }
            resyncAuditChain(loaded);
            RunRegistry.RunHolder holder = registry.registerLoaded(loaded);
            boolean live;
            holder.lock.lock();
            try {
                live = !holder.inFlight.isEmpty() || holder.lease != null
                        || holder.run.status() != RunStatus.RUNNING;
            } finally {
                holder.lock.unlock();
            }
            if (live) {
                continue;
            }
            boolean[] interrupted = {false};
            try {
                mutate(loaded.id(), tx -> {
                    Run run = tx.run;
                    if (run.status() != RunStatus.RUNNING || !tx.holder.inFlight.isEmpty()) {
                        return;
                    }
                    interrupted[0] = true;
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
                            RunMutation.data("resetStages", reset, "nodeId", tx.nodeId));
                    run.setStatus(RunStatus.SAFE_STOPPED);
                    run.setHalt(HaltReason.INTERRUPTED_BY_RESTART, "engine restarted while the run was in progress");
                    tx.audit(AuditEventType.SAFE_STOP, null, Actor.system(),
                            "run safe-stopped: INTERRUPTED_BY_RESTART",
                            RunMutation.data("haltReason", HaltReason.INTERRUPTED_BY_RESTART, "resetStages", reset));
                });
                if (interrupted[0]) {
                    recovered++;
                }
            } catch (RunLeasedElsewhereException e) {
                LOG.info("run not recovered: node {} acquired it meanwhile runId={}", e.holder(), loaded.id());
            } catch (InvalidRunStateException e) {
                LOG.info("run not recovered: {} runId={}", e.getMessage(), loaded.id());
            }
        }
        if (recovered == 0) {
            LOG.debug("recovery finished interruptedRuns=0 node={}", nodeId);
        } else {
            LOG.info("recovery finished interruptedRuns={} node={}", recovered, nodeId);
        }
        return recovered;
    }

    /**
     * Stops accepting commands, cancels in-flight attempts, releases the run leases this node holds (so that other
     * nodes can recover its runs right away; a failed release is retried once, after which the lease expires on its
     * own) and shuts down engine-owned executors. Runs that were in progress stay
     * RUNNING in the store and are recovered by {@link #recoverInterrupted()} on the next start (or by another
     * node).
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<RunRegistry.RunHolder> failedReleases = new ArrayList<>();
        for (RunRegistry.RunHolder holder : registry.all()) {
            holder.lock.lock();
            try {
                holder.inFlight.values().forEach(f -> f.future().cancel(true));
                holder.inFlight.clear();
                RunLease lease = holder.lease;
                if (lease != null && !tryRelease(lease)) {
                    holder.releasePending = true;
                    failedReleases.add(holder);
                } else {
                    holder.lease = null;
                    holder.releasePending = false;
                }
            } finally {
                holder.lock.unlock();
            }
        }
        for (RunRegistry.RunHolder holder : failedReleases) {
            // one retry, so that a transient failure does not leave the runs blocked for a whole lease period
            holder.lock.lock();
            try {
                RunLease lease = holder.lease;
                if (lease != null && tryRelease(lease)) {
                    holder.lease = null;
                    holder.releasePending = false;
                }
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

    /**
     * Runs one mutation of a run under its lock: resolves (or rehydrates) the run, takes or renews the run's lease
     * (refreshing the run from the store when the lease is newly acquired and the store holds a newer state), applies
     * the body, pumps, commits the run with the mutation's hash-chained audit events, and releases the lease unless
     * the run keeps executing (RUNNING). A mutation that does not commit rolls the audit chain back to the last
     * committed event.
     */
    private Run mutate(String runId, Consumer<RunMutation> body) {
        ensureOpen();
        while (true) {
            Run copy = mutateHolder(registry.holder(runId), body);
            if (copy != null) {
                return copy;
            }
            // evicted meanwhile: resolve the run again (from the store)
        }
    }

    /**
     * Runs one mutation on a given holder, as {@link #mutate} does, unless the holder was evicted meanwhile. Agent
     * attempts apply their results through it with the holder they were submitted to, so that a run that was
     * abandoned on this node (lease lost, failed commit) is never reloaded, re-leased or written by a stale result.
     *
     * @return the committed copy, or null when the holder was evicted
     */
    private @Nullable Run mutateHolder(RunRegistry.RunHolder holder, Consumer<RunMutation> body) {
        ensureOpen();
        String runId = holder.run.id();
        Run copy;
        Instant at;
        holder.lock.lock();
        try {
            if (holder.evicted) {
                return null;
            }
            if (holder.discarded) {
                // the creation of this run could not be committed; it is unregistered and must stay unpersisted
                throw new RunNotFoundException(runId);
            }
            RunMutation tx = null;
            boolean committed = false;
            try {
                ensureLease(holder);
                tx = new RunMutation(holder, now(), nodeId);
                body.accept(tx);
                pump(tx);
                tx.run.touch(tx.now);
                requireLeaseBeforeCommit(holder);
                try {
                    runStore.commit(tx.run, tx.events(), auditSink);
                } catch (RuntimeException e) {
                    throw commitFailed(holder, e);
                }
                committed = true;
                holder.committed = true;
                copy = tx.run.copy();
                at = tx.now;
            } finally {
                if (!committed && tx != null && !holder.evicted) {
                    tx.rollbackAuditChain();
                }
                if (!holder.evicted) {
                    releaseLeaseUnlessExecuting(holder);
                }
            }
        } finally {
            holder.lock.unlock();
        }
        notifyListener(copy);
        evictQuietly(at);
        return copy;
    }

    // =========================================================================================== leases and store

    /**
     * Makes sure this node holds the run's lease: renews the lease it holds, or acquires a new one and then refreshes
     * the run from the store if another node advanced it meanwhile. The refresh happens after the acquisition (even
     * for a run loaded a moment ago), because only then can no other node commit a newer state.
     *
     * <p>A lease kept after a failed release is renewed and used like any held lease. When no lease is held locally
     * but the provider names this node as the holder (a restart with a fixed node id), the lease is reclaimed
     * ({@link RunLeaseProvider#reclaim}): a node is never refused with its own node id.</p>
     *
     * @throws RunLeasedElsewhereException when another node holds a valid lease
     * @throws InvalidRunStateException when the previous lease of this node is still valid and the provider cannot
     *                                  reclaim it
     */
    private void ensureLease(RunRegistry.RunHolder holder) {
        String runId = holder.run.id();
        RunLease held = holder.lease;
        if (held != null) {
            if (leases.renew(held, config.leaseTtl())) {
                return;
            }
            if (!holder.releasePending) {
                // this node was executing the run: what it holds in memory may be superseded
                throw leaseLost(holder, "at the start of a mutation");
            }
            holder.lease = null;
            holder.releasePending = false;
            LOG.warn("run lease lost (expired and taken over or released) runId={} node={}", runId, nodeId);
        }
        Optional<RunLease> acquired = leases.tryAcquire(runId, nodeId, config.leaseTtl());
        if (acquired.isEmpty()) {
            String owner = leases.holderOf(runId).orElse("unknown");
            if (!nodeId.equals(owner)) {
                throw new RunLeasedElsewhereException(runId, owner);
            }
            acquired = leases.reclaim(runId, nodeId, config.leaseTtl());
            if (acquired.isEmpty()) {
                throw new InvalidRunStateException("run " + runId + " is still leased by this node (" + nodeId
                        + ") under a lease it no longer holds (a restart with the same node id) and the lease "
                        + "provider cannot reclaim it; retry once it expires (lease TTL " + config.leaseTtl() + ")");
            }
            LOG.info("run lease of this node reclaimed runId={} node={}", runId, nodeId);
        }
        holder.lease = acquired.get();
        holder.releasePending = false;
        if (holder.committed) {
            refreshIfStale(holder);
        }
    }

    /**
     * Renews the lease right before a commit: when this node no longer holds it (another node took it over after it
     * expired, or it is gone), the mutation must not be written.
     *
     * @throws RunLeasedElsewhereException when the lease is lost (the run was abandoned on this node)
     */
    private void requireLeaseBeforeCommit(RunRegistry.RunHolder holder) {
        RunLease lease = holder.lease;
        if (lease != null && !leases.renew(lease, config.leaseTtl())) {
            throw leaseLost(holder, "right before a commit");
        }
    }

    /**
     * Handles a lost lease of a run this node was executing: nothing is written, the run is abandoned on this node
     * (lease dropped without a release, in-flight attempts abandoned, registry copy evicted) and the returned exception
     * names the new holder. Called under the run lock.
     */
    private RunLeasedElsewhereException leaseLost(RunRegistry.RunHolder holder, String when) {
        String runId = holder.run.id();
        String owner = leases.holderOf(runId).orElse("unknown");
        LOG.warn("run lease lost {}; nothing is written, the run is abandoned on this node and reloaded on its next "
                + "use runId={} node={} holder={}", when, runId, nodeId, owner);
        abandon(holder, false);
        return new RunLeasedElsewhereException(runId, owner);
    }

    /**
     * Handles a failed commit: the store exception never reaches the caller as such. For a run that was never
     * committed (a failed {@code start}) the holder is discarded; otherwise the mutation is discarded with the
     * holder (in-flight attempts abandoned, lease released, registry copy evicted), so the next command reloads the
     * stored state. Called under the run lock.
     */
    private InvalidRunStateException commitFailed(RunRegistry.RunHolder holder, RuntimeException failure) {
        String runId = holder.run.id();
        String cause = safeFailureText(failure.toString());
        if (!holder.committed) {
            holder.discarded = true;
            return new InvalidRunStateException("run " + runId + " could not be persisted, so it was not created: "
                    + cause, failure);
        }
        LOG.warn("run commit failed; the mutation is discarded and the run is reloaded from the store on its next use "
                + "runId={} node={} error={}", runId, nodeId, cause);
        abandon(holder, true);
        return new InvalidRunStateException("run " + runId + " could not be persisted (" + cause + "); the change "
                + "was discarded and the stored state of the run is used from now on; retry the command", failure);
    }

    /**
     * Abandons the run on this node because its in-memory state can no longer be trusted: in-flight attempts are
     * abandoned (not interrupted, since this may run on the thread of one of them; their results are discarded), the
     * lease is dropped (released when {@code releaseLease}, so that recovery can take the run over) and the holder is
     * evicted from the registry. Called under the run lock.
     */
    private void abandon(RunRegistry.RunHolder holder, boolean releaseLease) {
        holder.inFlight.values().forEach(f -> f.future().cancel(false));
        holder.inFlight.clear();
        RunLease lease = holder.lease;
        holder.lease = null;
        holder.releasePending = false;
        if (releaseLease && lease != null) {
            tryRelease(lease);
        }
        registry.evictNow(holder);
    }

    /**
     * Called by an attempt whose heartbeat reported a lost lease: unless the run was abandoned or its lease changed
     * meanwhile, the lease is renewed once more and, when it is still lost, the run is abandoned on this node.
     */
    private void abandonIfLeaseLost(RunRegistry.RunHolder holder) {
        holder.lock.lock();
        try {
            RunLease lease = holder.lease;
            if (holder.evicted || lease == null || leases.renew(lease, config.leaseTtl())) {
                return;
            }
            leaseLost(holder, "while an attempt was running");
        } finally {
            holder.lock.unlock();
        }
    }

    /** Whether this engine holds a lease on the run locally (also one whose release is pending). */
    private boolean holdsLeaseLocally(String runId) {
        RunRegistry.RunHolder holder = registry.find(runId);
        return holder != null && holder.lease != null;
    }

    /**
     * Releases the lease of the run unless the run keeps executing on this node (RUNNING). Never fails the caller:
     * when the release fails, the lease is kept with a pending release (renewed and used by the next command of this
     * node, released later by {@link #releasePendingLeases()} or on close), so that this node never locks itself out.
     */
    private void releaseLeaseUnlessExecuting(RunRegistry.RunHolder holder) {
        RunLease lease = holder.lease;
        if (lease == null) {
            return;
        }
        if (!holder.discarded && holder.run.status() == RunStatus.RUNNING) {
            holder.releasePending = false; // executing here again: the lease is in use
            return;
        }
        if (tryRelease(lease)) {
            holder.lease = null;
            holder.releasePending = false;
        } else {
            holder.releasePending = true;
        }
    }

    /** Releases a lease; returns false (after logging) when the provider failed. */
    private boolean tryRelease(RunLease lease) {
        try {
            leases.release(lease);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("run lease release failed runId={} node={} error={}; kept as pending release (retried by "
                    + "maintenance and on close; otherwise it expires after {})", lease.runId(), nodeId, e.toString(),
                    config.leaseTtl());
            return false;
        }
    }

    /**
     * Retries the release of every lease this node kept because releasing it failed (the run is not executing here).
     * {@link #recoverInterruptedCount()} calls it first, so periodic maintenance retries pending releases without
     * further wiring; adapters may also call it directly.
     *
     * @return the number of leases released by this call
     */
    public int releasePendingLeases() {
        int released = 0;
        for (RunRegistry.RunHolder holder : registry.all()) {
            if (!holder.releasePending) {
                continue;
            }
            holder.lock.lock();
            try {
                RunLease lease = holder.lease;
                if (!holder.releasePending || lease == null) {
                    continue;
                }
                if (!holder.discarded && holder.run.status() == RunStatus.RUNNING) {
                    holder.releasePending = false;
                    continue;
                }
                if (tryRelease(lease)) {
                    holder.lease = null;
                    holder.releasePending = false;
                    released++;
                }
            } finally {
                holder.lock.unlock();
            }
        }
        if (released > 0) {
            LOG.info("pending run lease releases completed count={} node={}", released, nodeId);
        }
        return released;
    }

    /**
     * Renews the run's lease from an attempt's heartbeat (off the run lock). Returns false when the lease is lost (the
     * renewal was refused, or the run was abandoned on this node), which ends the attempt with LEASE_LOST.
     */
    private boolean renewLeaseFromAttempt(RunRegistry.RunHolder holder) {
        if (holder.evicted) {
            return false;
        }
        RunLease lease = holder.lease;
        return lease == null || leases.renew(lease, config.leaseTtl());
    }

    private Duration heartbeatInterval() {
        return Duration.ofMillis(Math.max(1L, config.leaseTtl().toMillis() / 3));
    }

    /**
     * Replaces the cached run by the store's state when the store holds a newer one (written by another node):
     * in-flight attempts of the superseded state are detached (their results are discarded when they finish). They
     * are not interrupted, because this may run on the thread of one of them (its completion re-acquiring the
     * lease), and interrupting a thread that is about to commit could break the store's I/O. Called under the run
     * lock.
     */
    private void refreshIfStale(RunRegistry.RunHolder holder) {
        Run current = holder.run;
        Optional<Run> stored = runStore.load(current.id());
        if (stored.isEmpty() || !newer(stored.get(), current)) {
            return;
        }
        Run fresh = stored.get();
        resyncAuditChain(fresh);
        holder.inFlight.values().forEach(f -> f.future().cancel(false));
        holder.inFlight.clear();
        holder.run = fresh;
        LOG.info("run refreshed from the store runId={} auditSeq={} -> {} status={}", current.id(), current.auditSeq(),
                fresh.auditSeq(), fresh.status());
    }

    /** Whether the stored state of a run is newer than the cached one (more audit events, or a later update). */
    private static boolean newer(Run stored, Run cached) {
        return stored.auditSeq() > cached.auditSeq()
                || (stored.auditSeq() == cached.auditSeq() && stored.updatedAt().isAfter(cached.updatedAt()));
    }

    /** Loads a run from the store for registration, with its audit chain head resynchronized with the audit log. */
    private Optional<Run> loadForRegistry(String runId) {
        Optional<Run> loaded = runStore.load(runId);
        loaded.ifPresent(this::resyncAuditChain);
        return loaded;
    }

    /**
     * Aligns the run's audit sequence and chain head with the last event the audit log actually holds (the log is
     * the source of truth: a snapshot may be ahead of it when a non-atomic commit saved the run but failed to append
     * the events). A bounded probe confirms the common case; otherwise {@link AuditSink#lastEvent} is used. Failures
     * are logged and leave the run unchanged.
     */
    private void resyncAuditChain(Run run) {
        try {
            long seq = run.auditSeq();
            List<AuditEvent> probe = auditSink.forRun(run.id(), Math.max(0L, seq - 1), 2);
            if (seq == 0 ? probe.isEmpty() : probe.size() == 1 && probe.getFirst().seq() == seq
                    && Objects.equals(probe.getFirst().hash(), run.auditHeadHash())) {
                return;
            }
            Optional<AuditEvent> last = auditSink.lastEvent(run.id());
            long lastSeq = last.map(AuditEvent::seq).orElse(0L);
            String lastHash = last.map(AuditEvent::hash).orElse(null);
            if (lastSeq != seq || !Objects.equals(lastHash, run.auditHeadHash())) {
                LOG.warn("audit chain head resynchronized with the audit log runId={} seq={} -> {}", run.id(), seq,
                        lastSeq);
                run.resetAuditChain(lastSeq, lastHash);
            }
        } catch (RuntimeException e) {
            LOG.warn("audit chain head could not be checked against the audit log runId={} error={}", run.id(),
                    e.toString());
        }
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
                                    RunMutation.data("phase", GatePhase.ENTRY, "rules", gate.ruleIds(),
                                            "policyPack", policyPackData));
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
        String agentSelection = "provider agent";
        String circuitProvider = null;
        if (AgentRegistry.FALLBACK_AGENT_ID.equals(exec.activeAgent())) {
            agent = agents.deterministic(def.role());
            agentSelection = "deterministic fallback after the primary agent was exhausted";
        } else if (def.role() == AgentRole.VERIFICATION && skeletonChangeSet(run)) {
            // A skeleton change plan (produced by the deterministic implementation agent, for example after a
            // fallback) cannot be meaningfully reviewed by an LLM verifier: it would reject the plan on every
            // feedback loop. Skeleton plans are verified structurally by the deterministic verifier instead.
            agent = agents.deterministic(def.role());
            exec.setActiveAgent(agent.id());
            agentSelection = "structural verification: the change set under review is a skeleton plan";
        } else {
            agent = agents.agentFor(def.role(), run.options().providerId());
            String providerId = run.options().providerId();
            boolean providerAgent = providerId != null && !AgentRegistry.DETERMINISTIC.equals(agent.id())
                    && !AgentRegistry.FALLBACK_AGENT_ID.equals(agent.id());
            ProviderCircuits.Decision decision = providerAgent ? circuits.admit(providerId, tx.now)
                    : ProviderCircuits.Decision.CALL_PROVIDER;
            if (decision == ProviderCircuits.Decision.FALLBACK) {
                engageCircuitFallback(tx, def, exec, agent.id(), providerId);
                agent = agents.deterministic(def.role());
                agentSelection = "deterministic fallback: the circuit of provider " + providerId + " is open";
            } else {
                exec.setActiveAgent(agent.id());
                if (decision == ProviderCircuits.Decision.TRIAL) {
                    agentSelection = "provider agent (half-open circuit trial)";
                }
                FaultSpec fault = run.options().faultFor(def.id()).orElse(null);
                if (fault != null && run.injectedFaults(def.id()) < fault.failTimes()) {
                    int injection = run.recordFaultInjection(def.id());
                    agent = new FaultInjectingStageAgent(agent, fault.kind(), injection, fault.failTimes());
                    if (providerAgent) {
                        circuits.recordInconclusive(providerId); // injected faults say nothing about the provider
                    }
                } else if (providerAgent) {
                    circuitProvider = providerId;
                }
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
        StageExecutor.PreparedContext prepared = stageExecutor.prepare(run, def, exec);
        StageContext context = prepared.context();
        // LLM provider agents get the stage timeout times llmTimeoutScale (long streamed outputs)
        Duration timeout = config.attemptTimeout(def.timeout(), agentId);
        Map<String, Object> startedData = RunMutation.data("attempt", exec.attempts(), "maxAttempts",
                def.retryPolicy().maxAttempts(), "agent", agentId, "agentSelection", agentSelection, "planVersion",
                run.planVersion(), "derivedFrom", context.derivedFrom(), "timeoutMs", timeout.toMillis(), "nodeId",
                tx.nodeId);
        startedData.putAll(impactedSourceAuditData(run, def, prepared));
        tx.audit(AuditEventType.STAGE_STARTED, def.id(), Actor.agent(agentId), "attempt " + exec.attempts() + " of "
                + def.id() + " started with " + agentId, startedData);
        String runId = run.id();
        String stageId = def.id();
        StageAgent chosen = agent;
        String breaker = circuitProvider;
        RunRegistry.RunHolder submittedTo = tx.holder;
        try {
            var future = executor.submit(() -> runAttempt(submittedTo, runId, stageId, token, chosen, agentId,
                    context, timeout, breaker));
            tx.holder.inFlight.put(stageId, new RunRegistry.InFlight(token, future));
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("engine is shutting down", e);
        }
        LOG.debug("stage started runId={} stageId={} attempt={} agent={}", runId, stageId, exec.attempts(), agentId);
    }

    /**
     * Returns the STAGE_STARTED data about the impacted sources an implementation, verification or security review
     * attempt of a REPO run is shown: {@code impactedSources} (paths in reading order, at most
     * {@value #MAX_AUDITED_SOURCES}) and {@code impactedSourceNotes} (what was cut or left out, at most
     * {@value #MAX_AUDITED_SOURCE_NOTES}, each at most 300 characters), plus the totals when a list was capped. Empty
     * for other stages and runs.
     */
    private static Map<String, Object> impactedSourceAuditData(Run run, StageDefinition def,
                                                               StageExecutor.PreparedContext prepared) {
        if (run.options().workspace() != WorkspaceMode.REPO || !StageExecutor.SOURCE_ROLES.contains(def.role())) {
            return Map.of();
        }
        List<String> shown = prepared.shownSources().stream().map(SecretPatterns::redact).toList();
        List<String> notes = prepared.context().impactedSourceNotes().stream()
                .map(n -> abbreviate(SecretPatterns.redact(n), 300)).toList();
        Map<String, Object> data = RunMutation.data("impactedSources",
                shown.subList(0, Math.min(shown.size(), MAX_AUDITED_SOURCES)), "impactedSourceNotes",
                notes.subList(0, Math.min(notes.size(), MAX_AUDITED_SOURCE_NOTES)));
        if (shown.size() > MAX_AUDITED_SOURCES) {
            data.put("impactedSourcesTotal", shown.size());
        }
        if (notes.size() > MAX_AUDITED_SOURCE_NOTES) {
            data.put("impactedSourceNotesTotal", notes.size());
        }
        return data;
    }

    /**
     * Whether the accepted change set under review is a skeleton plan (at least one change with
     * {@link ChangeSet.ContentKind#SKELETON} content).
     */
    private static boolean skeletonChangeSet(Run run) {
        return run.acceptedPayload(ChangeSet.class)
                .map(cs -> cs.changes().stream().anyMatch(c -> c.contentKind() == ChangeSet.ContentKind.SKELETON))
                .orElse(false);
    }

    /** Runs off the lock on a virtual thread; re-enters the lock to apply the outcome. */
    private void runAttempt(RunRegistry.RunHolder submittedTo, String runId, String stageId, long token,
                            StageAgent agent, String agentId, StageContext context, Duration timeout,
                            @Nullable String circuitProvider) {
        StageExecutor.AttemptOutcome outcome = stageExecutor.execute(agent, context, timeout,
                () -> renewLeaseFromAttempt(submittedTo), heartbeatInterval());
        if (circuitProvider != null) {
            // recorded before the result is applied, so that a retry scheduled by it already sees the new state
            recordCircuitOutcome(circuitProvider, runId, stageId, outcome);
        }
        if (outcome != null && outcome.leaseLost()) {
            // LEASE_LOST: like an interruption, nothing is applied or retried on this node
            abandonIfLeaseLost(submittedTo);
            LOG.warn("attempt abandoned: this node lost the run's lease runId={} stageId={} node={}", runId, stageId,
                    nodeId);
            return;
        }
        if (outcome == null || closed || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            if (mutateHolder(submittedTo, tx -> onAttemptFinished(tx, stageId, token, agentId, outcome)) == null) {
                // the run was abandoned on this node (lease lost, failed commit) or evicted: the result is stale
                LOG.debug("attempt result discarded: the run was abandoned on this node runId={} stageId={}", runId,
                        stageId);
            }
        } catch (RunNotFoundException e) {
            LOG.debug("attempt result discarded: the run is no longer registered runId={} stageId={}", runId,
                    stageId);
        } catch (RunLeasedElsewhereException e) {
            LOG.warn("attempt result discarded: node {} took over the run runId={} stageId={}", e.holder(), runId,
                    stageId);
        } catch (RuntimeException e) {
            if (closed) {
                LOG.debug("attempt result discarded after close runId={} stageId={}", runId, stageId);
                return;
            }
            LOG.error("applying attempt result failed runId={} stageId={}", runId, stageId, e);
            failAfterInternalError(submittedTo, runId, stageId, token, e);
        }
    }

    /**
     * Feeds the outcome of a provider attempt into the provider's circuit: a success (or clarification request)
     * closes it, a TRANSIENT, TIMEOUT or INVALID_OUTPUT failure counts, anything else (FATAL, cancelled) is
     * inconclusive.
     */
    private void recordCircuitOutcome(String providerId, String runId, String stageId,
                                      StageExecutor.@Nullable AttemptOutcome outcome) {
        try {
            StageResult attempted = outcome == null ? null : outcome.result();
            if (attempted == null) {
                circuits.recordInconclusive(providerId); // cancelled, or LEASE_LOST
                return;
            }
            ProviderCircuits.Transition transition = switch (attempted) {
                case StageResult.Success s -> circuits.recordSuccess(providerId);
                case StageResult.NeedsClarification c -> circuits.recordSuccess(providerId);
                case StageResult.Failure f when ProviderCircuits.counts(f.failure().kind()) ->
                        circuits.recordFailure(providerId, safeFailureText(f.failure().describe()), now());
                case StageResult.Failure f -> {
                    circuits.recordInconclusive(providerId);
                    yield ProviderCircuits.Transition.NONE;
                }
            };
            switch (transition) {
                case OPENED, REOPENED -> LOG.warn("provider circuit {} provider={} runId={} stageId={} "
                                + "failures={} cooldown={}", transition == ProviderCircuits.Transition.OPENED
                                ? "opened" : "re-opened", providerId, runId, stageId,
                        circuits.circuit(providerId, now()).consecutiveFailures(),
                        config.providerCircuitBreaker().cooldown());
                case CLOSED -> LOG.info("provider circuit closed provider={} runId={} stageId={}", providerId, runId,
                        stageId);
                case NONE -> {
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("provider circuit update failed provider={} error={}", providerId, e.toString());
        }
    }

    /**
     * Routes an attempt to the deterministic agent because the provider's circuit is open: the stage continues with
     * the deterministic fallback (fresh attempt count), audited as FALLBACK_ENGAGED with reason {@code circuit open}.
     */
    private void engageCircuitFallback(RunMutation tx, StageDefinition def, StageExecution exec, String from,
                                       String providerId) {
        ProviderCircuit circuit = circuits.circuit(providerId, tx.now);
        exec.setActiveAgent(AgentRegistry.FALLBACK_AGENT_ID);
        exec.setAttempts(0);
        tx.run.updateCounters(RunCounters::plusFallback);
        safeMetric(() -> metrics.fallback(def.id()));
        tx.audit(AuditEventType.FALLBACK_ENGAGED, def.id(), Actor.system(), "circuit of provider " + providerId
                        + " is open (" + circuit.consecutiveFailures() + " consecutive provider failures); "
                        + "deterministic fallback engaged without calling " + from,
                RunMutation.data("from", from, "to", AgentRegistry.FALLBACK_AGENT_ID, "reason", "circuit open",
                        "providerId", providerId, "consecutiveFailures", circuit.consecutiveFailures(), "openedAt",
                        circuit.openedAt(), "retryAt", circuit.retryAt(), "lastFailure", circuit.lastFailure()));
        LOG.info("circuit open: deterministic fallback runId={} stageId={} provider={}", tx.run.id(), def.id(),
                providerId);
    }

    private void failAfterInternalError(RunRegistry.RunHolder submittedTo, String runId, String stageId, long token,
                                        RuntimeException error) {
        try {
            // only on the holder the attempt ran for: a run abandoned on this node is recovered from the store
            mutateHolder(submittedTo, tx -> {
                StageExecution exec = tx.run.stage(stageId);
                if (exec.status() != StageStatus.RUNNING || exec.token() != token || !tx.run.status().isActive()) {
                    return;
                }
                tx.holder.inFlight.remove(stageId);
                failStage(tx, exec, safeFailureText("internal engine error: " + error),
                        HaltReason.AGENT_FAILURE_EXHAUSTED);
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
        StageResult result = Objects.requireNonNull(outcome.result(), "only completed attempts are applied");
        LlmTrace trace = switch (result) {
            case StageResult.Success s -> s.llm();
            case StageResult.NeedsClarification c -> c.llm();
            case StageResult.Failure f -> f.failure().trace();
        };
        // The cost is computed once, when the usage is recorded, with the price book of this moment.
        Long cost = trace != null ? priceOf(trace) : null;
        if (trace != null) {
            run.updateCounters(c -> c.plusLlmUsage(trace.inputTokens(), trace.outputTokens(),
                    trace.cacheReadTokens(), trace.cacheCreationTokens(), cost));
        }
        String provider = trace != null ? trace.providerId() : providerOf(agentId);
        long in = trace != null ? trace.inputTokens() : 0L;
        long out = trace != null ? trace.outputTokens() : 0L;
        long cacheRead = trace != null ? trace.cacheReadTokens() : 0L;
        long cacheCreation = trace != null ? trace.cacheCreationTokens() : 0L;
        safeMetric(() -> metrics.agentCall(provider, def.role(), outcome.latencyMs(), in, out, cacheRead,
                cacheCreation));
        if (trace != null) {
            safeMetric(() -> metrics.llmCost(provider, def.role(), cost));
        }
        Map<String, Object> llmData = llmAuditData(trace, cost);
        switch (result) {
            case StageResult.Success s -> handleSuccess(tx, def, exec, s, outcome, agentId, cost, llmData);
            case StageResult.NeedsClarification c -> handleClarification(tx, exec, c, agentId, llmData);
            case StageResult.Failure f -> handleFailure(tx, def, exec, f.failure());
        }
        StageStatus status = exec.status();
        safeMetric(() -> metrics.stageCompleted(stageId, status, outcome.latencyMs()));
    }

    /** Prices an LLM call with the configured price book; null when no price applies (or pricing failed). */
    private @Nullable Long priceOf(LlmTrace trace) {
        try {
            return config.priceBook().costMicroUsd(trace.providerId(), trace.model(), trace.inputTokens(),
                    trace.outputTokens(), trace.cacheReadTokens(), trace.cacheCreationTokens()).orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("LLM call could not be priced provider={} model={} error={}", trace.providerId(), trace.model(),
                    e.toString());
            return null;
        }
    }

    /**
     * Returns the LLM usage data of an attempt for its AGENT_DECISION audit event: provider, model, token counts
     * (prompt cache reads and writes included) and {@code costMicroUsd} when the call was priced; empty without an
     * LLM trace.
     */
    private static Map<String, Object> llmAuditData(@Nullable LlmTrace trace, @Nullable Long cost) {
        if (trace == null) {
            return Map.of();
        }
        return RunMutation.data("provider", trace.providerId(), "model", trace.model(), "inputTokens",
                trace.inputTokens(), "outputTokens", trace.outputTokens(), "cacheReadTokens", trace.cacheReadTokens(),
                "cacheCreationTokens", trace.cacheCreationTokens(), "costMicroUsd", cost);
    }

    private void handleSuccess(RunMutation tx, StageDefinition def, StageExecution exec, StageResult.Success success,
                               StageExecutor.AttemptOutcome outcome, String agentId, @Nullable Long cost,
                               Map<String, Object> llmData) {
        Run run = tx.run;
        ArtifactPayload payload = success.payload();
        try {
            if (payload.kind() != def.role().artifactKind()) {
                throw new StageFailure(FailureKind.INVALID_OUTPUT, "stage " + def.id() + " must produce "
                        + def.role().artifactKind() + " but produced " + payload.kind(), null, success.llm());
            }
            payload.validate();
        } catch (InvalidArtifactException e) {
            handleFailure(tx, def, exec, new StageFailure(FailureKind.INVALID_OUTPUT, e.getMessage(), e, success.llm(),
                    payload instanceof ChangeSet changeSet ? changeSet.requestedSources() : List.of()));
            return;
        } catch (StageFailure f) {
            handleFailure(tx, def, exec, f);
            return;
        }
        LlmTrace trace = success.llm();
        Provenance provenance = new Provenance(outcome.context().derivedFrom(), def.role(), agentId,
                trace != null ? trace.providerId() : providerOf(agentId), trace != null ? trace.model() : null,
                trace != null ? trace.promptHash() : null, trace != null ? trace.inputTokens() : 0L,
                trace != null ? trace.outputTokens() : 0L, outcome.latencyMs(), exec.attempts(), run.planVersion(),
                trace != null ? trace.cacheReadTokens() : 0L, trace != null ? trace.cacheCreationTokens() : 0L,
                trace != null ? cost : Long.valueOf(0L));
        Artifact previous = run.acceptedArtifact(def.id()).orElse(null);
        Artifact artifact = new Artifact(Ids.newId(), run.id(), def.id(), payload.kind(),
                run.nextArtifactVersion(def.id()), run.planVersion(), codec.hash(payload), payload, provenance,
                tx.now);
        run.addArtifact(artifact);
        Map<String, Object> decisionData = RunMutation.data("artifactId", artifact.id(), "version", artifact.version(),
                "kind", artifact.kind(), "hash", artifact.hash(), "latencyMs", outcome.latencyMs(), "compensations",
                success.compensations().size());
        decisionData.putAll(llmData);
        tx.audit(AuditEventType.AGENT_DECISION, def.id(), Actor.agent(agentId), auditableRationale(success.rationale()),
                decisionData);
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
                            artifact.id(), "hash", artifact.hash(), "removed", true, "policyPack", policyPackData));
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
                                     String agentId, Map<String, Object> llmData) {
        Run run = tx.run;
        exec.setStatus(StageStatus.AWAITING_CLARIFICATION);
        run.setClarification(new Clarification(exec.stageId(), request.questions(), Map.of(), tx.now, null, null));
        Map<String, Object> decisionData = RunMutation.data("needsClarification", true);
        decisionData.putAll(llmData);
        tx.audit(AuditEventType.AGENT_DECISION, exec.stageId(), Actor.agent(agentId),
                auditableRationale(request.rationale()), decisionData);
        tx.audit(AuditEventType.CLARIFICATION_REQUESTED, exec.stageId(), Actor.system(),
                request.questions().size() + " clarification questions asked",
                RunMutation.data("questionIds", request.questions().stream().map(ClarificationQuestion::id).toList()));
    }

    /**
     * Retry (retryable failure with attempts left), else fallback to the deterministic agent (when enabled and
     * not already engaged), else stage failure with rollback and safe-stop. When the failure names requested sources
     * (for example those of a change set the sandbox rejected), the stage's last error gets a
     * {@link StageExecutor#REQUESTED_SOURCES_PREFIX} line, so that the next attempt shows those files, and
     * RETRY_SCHEDULED records them as {@code requestedSources}.
     */
    private void handleFailure(RunMutation tx, StageDefinition def, StageExecution exec, StageFailure failure) {
        Run run = tx.run;
        // agent and provider messages may carry credentials or large payloads: never persist or audit them verbatim
        String described = safeFailureText(failure.describe());
        String error = failure.getMessage() == null ? null : safeFailureText(failure.getMessage());
        String requested = StageExecutor.requestedSourcesLine(failure.requestedSources());
        exec.setLastError(requested == null ? described : described + "\n" + requested);
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
                            + " failed (" + described + "); retry in " + backoff.toMillis() + " ms",
                    RunMutation.data("attempt", exec.attempts(), "kind", failure.kind(), "error", error,
                            "backoffMs", backoff.toMillis(), "nextAttemptAt", exec.nextAttemptAt(),
                            "requestedSources", requested == null ? null
                                    : StageExecutor.requestedSourcesIn(requested)));
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
                            + " exhausted (" + described + "); deterministic fallback engaged",
                    RunMutation.data("from", from, "to", AgentRegistry.FALLBACK_AGENT_ID, "kind", failure.kind(),
                            "error", error));
            submitAttempt(tx, def, exec);
            return;
        }
        failStage(tx, exec, (failure.retryable() ? "attempts exhausted: " : "non-retryable failure: ")
                + described, HaltReason.AGENT_FAILURE_EXHAUSTED);
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
                ReleaseReadiness.ChangeRecord finalRecord = new ReleaseReadiness.ChangeRecord(record.summary(),
                        record.riskLevel(), record.rollbackPlan(), records, artifactIds, verdict);
                ChangeRecordSigner signer = config.changeRecordSigner();
                ReleaseReadiness.Signature signature = null;
                String signatureError = null;
                if (!UnsignedChangeRecordSigner.ALGORITHM.equals(signer.algorithm())) {
                    try {
                        String value = signer.sign(ChangeRecordSigner.canonicalPayload(finalRecord));
                        signature = new ReleaseReadiness.Signature(signer.algorithm(), signer.keyId(), value,
                                tx.now.toString());
                    } catch (RuntimeException e) {
                        // never block completion on the signer; the missing signature is visible and audited
                        signatureError = e.getClass().getSimpleName() + ": " + e.getMessage();
                        LOG.error("change record signing failed runId={} keyId={}", run.id(), signer.keyId(), e);
                    }
                }
                ReleaseReadiness finalized = release.withChangeRecord(finalRecord.withSignature(signature));
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
                                + " accepted: change record finalized with " + records.size() + " approvals"
                                + (signature != null ? " and signed (" + signature.algorithm() + ", key "
                                + signature.keyId() + ")" : signatureError != null ? " (signing failed)" : ""),
                        RunMutation.data("artifactId", artifact.id(), "version", artifact.version(), "hash",
                                artifact.hash(), "approvals", records.size(), "signatureAlgorithm",
                                signature != null ? signature.algorithm() : UnsignedChangeRecordSigner.ALGORITHM,
                                "keyId", signature != null ? signature.keyId() : UnsignedChangeRecordSigner.ALGORITHM,
                                "signer", signer.algorithm(), "signatureError", signatureError));
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
        if (closed || (!isCached(runId) && !storedWakeUpTarget(runId, stageId, token))) {
            // the run is not cached here and its stored state no longer waits for this retry (an idle run that was
            // evicted, or one another node took over): the wake-up is obsolete (resume re-arms retries). A run that
            // was dropped after a failed commit still waits for it in the store and is reloaded by the mutation.
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
        } catch (RunLeasedElsewhereException e) {
            LOG.info("retry wake-up dropped: node {} executes the run runId={} stageId={}", e.holder(), runId,
                    stageId);
        } catch (RuntimeException e) {
            if (!closed) {
                onWakeUpFailed(runId, stageId, token, delay, failedWakeUps, e);
            }
        }
    }

    /** Whether the stored state of a run still waits for this retry wake-up (true when the store cannot tell). */
    private boolean storedWakeUpTarget(String runId, String stageId, long token) {
        try {
            return runStore.load(runId).map(run -> graph.contains(stageId)
                    && isWakeUpTarget(run, run.stage(stageId), token)).orElse(false);
        } catch (RuntimeException e) {
            return true; // let the mutation decide (and fail, and be retried, while the store is unavailable)
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
                failStage(tx, exec, safeFailureText("retry wake-up failed " + (failedWakeUps + 1) + " times: "
                                + error),
                        HaltReason.AGENT_FAILURE_EXHAUSTED);
            });
        } catch (RuntimeException e) {
            if (!closed) {
                LOG.error("could not fail the stage after failed retry wake-ups runId={} stageId={}", runId, stageId,
                        e);
            }
        }
    }

    /**
     * Whether a stage still waits for this retry wake-up: RETRY_SCHEDULED in an active run, with the wake-up's token,
     * or with no token at all (tokens live in memory only: the run was reloaded from the store, for example after a
     * failed commit dropped the in-memory copy, so the stored retry is adopted by the first wake-up that finds it).
     */
    private static boolean isWakeUpTarget(Run run, StageExecution exec, long token) {
        return exec.status() == StageStatus.RETRY_SCHEDULED && (exec.token() == token || exec.token() == 0L)
                && run.status().isActive();
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

    /**
     * Returns a failure text that is safe to persist in the run (last error), to audit, to log and to show in circuit
     * snapshots: credential-like tokens redacted ({@link SecretPatterns#redact}) and at most
     * {@value #MAX_FAILURE_TEXT} characters (a provider error may echo a request or response body).
     */
    static String safeFailureText(String text) {
        return abbreviate(SecretPatterns.redact(text), MAX_FAILURE_TEXT);
    }

    private static void safeMetric(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.warn("metrics recorder failed error={}", e.toString());
        }
    }
}
