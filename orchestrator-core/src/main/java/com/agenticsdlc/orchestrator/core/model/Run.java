package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.util.Clocks;

/**
 * The run aggregate: everything the engine knows about one execution of the workflow.
 *
 * <p>{@code Run} is mutable and is mutated only by the engine while it holds the run's lock. Everything
 * the engine hands out ({@code OrchestrationEngine.get/list}, listener callbacks) is a {@link #copy()}, so
 * callers can read and serialize it freely. For persistence use {@link #toSnapshot()} and
 * {@link #fromSnapshot(RunSnapshot, WorkflowGraph)}.</p>
 *
 * <p>Methods in the "mutators" section are engine internals; they are public only because the engine
 * lives in another package.</p>
 */
public final class Run {

    private final String id;
    private final String workflowId;
    private final int workflowVersion;
    private final String scenarioKey;
    private final String requirement;
    private final RunOptions options;
    private final RunBudget budget;
    private final Instant createdAt;

    private String activeRequirement;
    private RunStatus status;
    private @Nullable HaltReason haltReason;
    private @Nullable String haltDetail;
    private int planVersion;
    private @Nullable Instant startedAt;
    private @Nullable Instant endedAt;
    private Instant updatedAt;

    private final LinkedHashMap<String, StageExecution> stages;
    private final List<Artifact> artifacts;
    private final LinkedHashMap<String, Integer> acceptedVersions;
    private final List<Approval> approvals;
    private @Nullable Clarification clarification;
    private final Deque<Checkpoint> checkpoints;
    private final List<Compensation> compensations;
    private RunCounters counters;
    private final List<Interval> humanWaitIntervals;
    private final List<PolicyFindingRecord> policyFindings;
    private final LinkedHashMap<String, Integer> injectedFaults;
    private @Nullable Instant firstFailureAt;
    private @Nullable String firstFailureStageId;
    private @Nullable Instant recoveredAt;
    private long auditSeq;

    private Run(String id, String workflowId, int workflowVersion, String scenarioKey, String requirement,
                RunOptions options, RunBudget budget, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.scenarioKey = scenarioKey;
        this.requirement = requirement;
        this.options = options;
        this.budget = budget;
        this.createdAt = createdAt;
        this.activeRequirement = requirement;
        this.status = RunStatus.RUNNING;
        this.planVersion = 1;
        this.updatedAt = createdAt;
        this.stages = new LinkedHashMap<>();
        this.artifacts = new ArrayList<>();
        this.acceptedVersions = new LinkedHashMap<>();
        this.approvals = new ArrayList<>();
        this.checkpoints = new ArrayDeque<>();
        this.compensations = new ArrayList<>();
        this.counters = RunCounters.ZERO;
        this.humanWaitIntervals = new ArrayList<>();
        this.policyFindings = new ArrayList<>();
        this.injectedFaults = new LinkedHashMap<>();
    }

    /**
     * Creates a new run with one PENDING stage execution per stage of the graph. The status is RUNNING
     * until the engine evaluates the run-start policy.
     *
     * @param id run id
     * @param graph workflow graph
     * @param scenarioKey scenario key
     * @param requirement requirement text
     * @param options run options
     * @param now creation time
     * @return the new run
     */
    public static Run create(String id, WorkflowGraph graph, String scenarioKey, String requirement, RunOptions options,
                             Instant now) {
        RunBudget budget = options.budgetOverride() != null ? options.budgetOverride() : RunBudget.DEFAULT;
        Run run = new Run(id, graph.definition().id(), graph.definition().version(), scenarioKey, requirement,
                options, budget, now);
        for (StageDefinition stage : graph.stages()) {
            run.stages.put(stage.id(), new StageExecution(stage.id()));
        }
        return run;
    }

    // ------------------------------------------------------------------ snapshots and copies

    /**
     * Returns a deep copy. Immutable members (artifacts, approvals, checkpoints, ...) are shared.
     *
     * @return the copy
     */
    public Run copy() {
        Run copy = new Run(id, workflowId, workflowVersion, scenarioKey, requirement, options, budget, createdAt);
        copy.activeRequirement = activeRequirement;
        copy.status = status;
        copy.haltReason = haltReason;
        copy.haltDetail = haltDetail;
        copy.planVersion = planVersion;
        copy.startedAt = startedAt;
        copy.endedAt = endedAt;
        copy.updatedAt = updatedAt;
        stages.forEach((k, v) -> copy.stages.put(k, v.copy()));
        copy.artifacts.addAll(artifacts);
        copy.acceptedVersions.putAll(acceptedVersions);
        copy.approvals.addAll(approvals);
        copy.clarification = clarification;
        copy.checkpoints.addAll(checkpoints);
        copy.compensations.addAll(compensations);
        copy.counters = counters;
        copy.humanWaitIntervals.addAll(humanWaitIntervals);
        copy.policyFindings.addAll(policyFindings);
        copy.injectedFaults.putAll(injectedFaults);
        copy.firstFailureAt = firstFailureAt;
        copy.firstFailureStageId = firstFailureStageId;
        copy.recoveredAt = recoveredAt;
        copy.auditSeq = auditSeq;
        return copy;
    }

    /**
     * Returns the snapshot of this run using the standard codec.
     *
     * @return the snapshot
     */
    public RunSnapshot toSnapshot() {
        return toSnapshot(JsonCodec.standard());
    }

    /**
     * Returns the snapshot of this run.
     *
     * @param codec codec used to serialize artifact payloads
     * @return the snapshot
     */
    public RunSnapshot toSnapshot(JsonCodec codec) {
        List<RunSnapshot.StageSnapshot> stageSnapshots = stages.values().stream()
                .map(s -> new RunSnapshot.StageSnapshot(s.stageId(), s.status(), s.attempts(), s.activeAgent(),
                        s.startedAt(), s.endedAt(), s.lastError(), s.feedback(), s.nextAttemptAt(),
                        s.pendingApproval()))
                .toList();
        List<RunSnapshot.ArtifactSnapshot> artifactSnapshots = artifacts.stream()
                .map(a -> new RunSnapshot.ArtifactSnapshot(a.id(), a.runId(), a.stageId(), a.kind(), a.version(),
                        a.planVersion(), a.hash(), codec.canonicalJson(a.payload()), a.provenance(), a.createdAt()))
                .toList();
        return new RunSnapshot(id, workflowId, workflowVersion, scenarioKey, requirement, activeRequirement, options,
                budget, status, haltReason, haltDetail, planVersion, createdAt, startedAt, endedAt, updatedAt,
                stageSnapshots, artifactSnapshots, acceptedVersions, approvals, clarification,
                new ArrayList<>(checkpoints), compensations, counters, humanWaitIntervals, policyFindings,
                injectedFaults, firstFailureAt, firstFailureStageId, recoveredAt, auditSeq);
    }

    /**
     * Restores a run from a snapshot using the standard codec.
     *
     * @param snapshot the snapshot
     * @param graph the workflow graph the run was created with
     * @return the run
     */
    public static Run fromSnapshot(RunSnapshot snapshot, WorkflowGraph graph) {
        return fromSnapshot(snapshot, graph, JsonCodec.standard());
    }

    /**
     * Restores a run from a snapshot. Stage executions are ordered by the graph; stages added to the graph
     * after the snapshot was taken start as PENDING.
     *
     * @param snapshot the snapshot
     * @param graph the workflow graph the run was created with
     * @param codec codec used to parse artifact payloads
     * @return the run
     * @throws IllegalArgumentException when the snapshot belongs to another workflow or references unknown stages
     */
    public static Run fromSnapshot(RunSnapshot snapshot, WorkflowGraph graph, JsonCodec codec) {
        if (!graph.definition().id().equals(snapshot.workflowId())) {
            throw new IllegalArgumentException("Snapshot of run " + snapshot.id() + " belongs to workflow "
                    + snapshot.workflowId() + ", not " + graph.definition().id());
        }
        Run run = new Run(snapshot.id(), snapshot.workflowId(), snapshot.workflowVersion(), snapshot.scenarioKey(),
                snapshot.requirement(), snapshot.options(), snapshot.budget(), snapshot.createdAt());
        run.activeRequirement = snapshot.activeRequirement();
        run.status = snapshot.status();
        run.haltReason = snapshot.haltReason();
        run.haltDetail = snapshot.haltDetail();
        run.planVersion = snapshot.planVersion();
        run.startedAt = snapshot.startedAt();
        run.endedAt = snapshot.endedAt();
        run.updatedAt = snapshot.updatedAt();
        Map<String, RunSnapshot.StageSnapshot> byId = new LinkedHashMap<>();
        for (RunSnapshot.StageSnapshot s : snapshot.stages()) {
            if (!graph.contains(s.stageId())) {
                throw new IllegalArgumentException("Snapshot of run " + snapshot.id() + " references unknown stage "
                        + s.stageId());
            }
            byId.put(s.stageId(), s);
        }
        for (StageDefinition def : graph.stages()) {
            RunSnapshot.StageSnapshot s = byId.get(def.id());
            run.stages.put(def.id(), s == null ? new StageExecution(def.id())
                    : new StageExecution(s.stageId(), s.status(), s.attempts(), s.activeAgent(), s.startedAt(),
                    s.endedAt(), s.lastError(), s.feedback(), s.nextAttemptAt(), s.pendingApproval()));
        }
        for (RunSnapshot.ArtifactSnapshot a : snapshot.artifacts()) {
            ArtifactPayload payload = codec.parsePayload(a.kind(), a.payloadJson());
            run.artifacts.add(new Artifact(a.id(), a.runId(), a.stageId(), a.kind(), a.version(), a.planVersion(),
                    a.hash(), payload, a.provenance(), a.createdAt()));
        }
        run.acceptedVersions.putAll(snapshot.acceptedVersions());
        run.approvals.addAll(snapshot.approvals());
        run.clarification = snapshot.clarification();
        run.checkpoints.addAll(snapshot.checkpoints());
        run.compensations.addAll(snapshot.compensations());
        run.counters = snapshot.counters();
        run.humanWaitIntervals.addAll(snapshot.humanWaitIntervals());
        run.policyFindings.addAll(snapshot.policyFindings());
        run.injectedFaults.putAll(snapshot.injectedFaults());
        run.firstFailureAt = snapshot.firstFailureAt();
        run.firstFailureStageId = snapshot.firstFailureStageId();
        run.recoveredAt = snapshot.recoveredAt();
        run.auditSeq = snapshot.auditSeq();
        return run;
    }

    // ------------------------------------------------------------------ accessors

    /** @return the run id */
    public String id() {
        return id;
    }

    /** @return the workflow id */
    public String workflowId() {
        return workflowId;
    }

    /** @return the workflow version */
    public int workflowVersion() {
        return workflowVersion;
    }

    /** @return the scenario key */
    public String scenarioKey() {
        return scenarioKey;
    }

    /** @return the original requirement */
    public String requirement() {
        return requirement;
    }

    /** @return the active requirement (changed by re-planning) */
    public String activeRequirement() {
        return activeRequirement;
    }

    /** @return the run options */
    public RunOptions options() {
        return options;
    }

    /** @return the effective budget */
    public RunBudget budget() {
        return budget;
    }

    /** @return the run status */
    public RunStatus status() {
        return status;
    }

    /** @return the halt reason while safe-stopped */
    public @Nullable HaltReason haltReason() {
        return haltReason;
    }

    /** @return the halt or abort detail */
    public @Nullable String haltDetail() {
        return haltDetail;
    }

    /** @return the plan version (starts at 1, incremented by re-planning) */
    public int planVersion() {
        return planVersion;
    }

    /** @return the creation time */
    public Instant createdAt() {
        return createdAt;
    }

    /** @return the time the run first started executing */
    public @Nullable Instant startedAt() {
        return startedAt;
    }

    /** @return the completion or abort time */
    public @Nullable Instant endedAt() {
        return endedAt;
    }

    /** @return the last mutation time */
    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Returns the stage executions in definition order.
     *
     * @return unmodifiable list
     */
    public List<StageExecution> stages() {
        return List.copyOf(stages.values());
    }

    /**
     * Returns the stage execution of a stage.
     *
     * @param stageId stage id
     * @return the execution
     * @throws IllegalArgumentException when unknown
     */
    public StageExecution stage(String stageId) {
        StageExecution stage = stages.get(stageId);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage " + stageId);
        }
        return stage;
    }

    /**
     * Returns whether the run has the stage.
     *
     * @param stageId stage id
     * @return true when known
     */
    public boolean hasStage(String stageId) {
        return stages.containsKey(stageId);
    }

    /** @return the full artifact history (unmodifiable) */
    public List<Artifact> artifacts() {
        return Collections.unmodifiableList(artifacts);
    }

    /** @return stage id to accepted artifact version (unmodifiable) */
    public Map<String, Integer> acceptedVersions() {
        return Collections.unmodifiableMap(acceptedVersions);
    }

    /**
     * Returns the artifact of a stage with a given version.
     *
     * @param stageId stage id
     * @param version version
     * @return the artifact
     */
    public Optional<Artifact> artifact(String stageId, int version) {
        return artifacts.stream().filter(a -> a.stageId().equals(stageId) && a.version() == version).findFirst();
    }

    /**
     * Returns the accepted artifact of a stage.
     *
     * @param stageId stage id
     * @return the accepted artifact
     */
    public Optional<Artifact> acceptedArtifact(String stageId) {
        Integer version = acceptedVersions.get(stageId);
        return version == null ? Optional.empty() : artifact(stageId, version);
    }

    /**
     * Returns the accepted artifacts in stage order.
     *
     * @return accepted artifacts
     */
    public List<Artifact> acceptedArtifacts() {
        List<Artifact> result = new ArrayList<>();
        for (String stageId : stages.keySet()) {
            acceptedArtifact(stageId).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * Returns the payload of the first accepted artifact (in stage order) of the given type.
     *
     * @param type payload type
     * @param <T> payload type
     * @return the payload
     */
    public <T extends ArtifactPayload> Optional<T> acceptedPayload(Class<T> type) {
        return acceptedArtifacts().stream().map(Artifact::payload).filter(type::isInstance).map(type::cast)
                .findFirst();
    }

    /**
     * Returns the highest artifact version ever produced by a stage.
     *
     * @param stageId stage id
     * @return the version, 0 when none
     */
    public int maxArtifactVersion(String stageId) {
        return artifacts.stream().filter(a -> a.stageId().equals(stageId)).mapToInt(Artifact::version).max()
                .orElse(0);
    }

    /** @return all approvals, oldest first (unmodifiable) */
    public List<Approval> approvals() {
        return Collections.unmodifiableList(approvals);
    }

    /**
     * Returns the pending approvals.
     *
     * @return approvals with status PENDING
     */
    public List<Approval> pendingApprovals() {
        return approvals.stream().filter(a -> a.status() == ApprovalStatus.PENDING).toList();
    }

    /** @return the clarification, if any */
    public @Nullable Clarification clarification() {
        return clarification;
    }

    /** @return checkpoints, oldest first */
    public List<Checkpoint> checkpoints() {
        return List.copyOf(checkpoints);
    }

    /**
     * Returns the latest checkpoint of a stage.
     *
     * @param stageId stage id
     * @return the checkpoint
     */
    public Optional<Checkpoint> latestCheckpointFor(String stageId) {
        Iterator<Checkpoint> it = checkpoints.descendingIterator();
        while (it.hasNext()) {
            Checkpoint checkpoint = it.next();
            if (checkpoint.stageId().equals(stageId)) {
                return Optional.of(checkpoint);
            }
        }
        return Optional.empty();
    }

    /** @return registered compensations, oldest first (unmodifiable) */
    public List<Compensation> compensations() {
        return Collections.unmodifiableList(compensations);
    }

    /** @return the counters */
    public RunCounters counters() {
        return counters;
    }

    /** @return human-wait intervals (unmodifiable) */
    public List<Interval> humanWaitIntervals() {
        return Collections.unmodifiableList(humanWaitIntervals);
    }

    /** @return recorded policy findings (unmodifiable) */
    public List<PolicyFindingRecord> policyFindings() {
        return Collections.unmodifiableList(policyFindings);
    }

    /**
     * Returns how many faults were injected into a stage so far.
     *
     * @param stageId stage id
     * @return count
     */
    public int injectedFaults(String stageId) {
        return injectedFaults.getOrDefault(stageId, 0);
    }

    /** @return the first failed attempt time */
    public @Nullable Instant firstFailureAt() {
        return firstFailureAt;
    }

    /** @return the stage of the first failed attempt */
    public @Nullable String firstFailureStageId() {
        return firstFailureStageId;
    }

    /** @return the time the first failed stage later succeeded */
    public @Nullable Instant recoveredAt() {
        return recoveredAt;
    }

    /** @return the last audit sequence number used */
    public long auditSeq() {
        return auditSeq;
    }

    /**
     * Returns the run's current risk: the preliminary requirement risk merged with the accepted design risk.
     *
     * @return the risk assessment (LOW when nothing is accepted yet)
     */
    public RiskAssessment currentRisk() {
        RiskAssessment risk = RiskAssessment.low();
        Optional<RequirementSpec> spec = acceptedPayload(RequirementSpec.class);
        if (spec.isPresent() && spec.get().preliminaryRisk() != null) {
            risk = risk.merge(spec.get().preliminaryRisk());
        }
        Optional<DesignSpec> design = acceptedPayload(DesignSpec.class);
        if (design.isPresent() && design.get().risk() != null) {
            risk = risk.merge(design.get().risk());
        }
        return risk;
    }

    /**
     * Returns the total human-wait time.
     *
     * @param now current time (closes open intervals for the computation)
     * @return milliseconds
     */
    public long humanWaitMs(Instant now) {
        return humanWaitIntervals.stream().mapToLong(i -> i.durationMs(now)).sum();
    }

    /**
     * Returns the end-to-end latency.
     *
     * @return {@code endedAt - startedAt} in milliseconds, or null while not ended
     */
    public @Nullable Long endToEndMs() {
        return Clocks.millisBetweenOrNull(startedAt, endedAt);
    }

    /**
     * Returns the active time: elapsed time since start minus human-wait time.
     *
     * @param now current time (used while the run has not ended)
     * @return milliseconds (0 before start)
     */
    public long activeMs(Instant now) {
        if (startedAt == null) {
            return 0L;
        }
        Instant end = endedAt != null ? endedAt : now;
        return Math.max(0L, Clocks.millisBetween(startedAt, end) - humanWaitMs(end));
    }

    /**
     * Returns the time to recover from the first failure.
     *
     * @return {@code recoveredAt - firstFailureAt}, or null
     */
    public @Nullable Long mttrMs() {
        return Clocks.millisBetweenOrNull(firstFailureAt, recoveredAt);
    }

    // ------------------------------------------------------------------ mutators (engine only)

    /**
     * Sets the active requirement.
     *
     * @param value requirement
     */
    public void setActiveRequirement(String value) {
        this.activeRequirement = Objects.requireNonNull(value);
    }

    /**
     * Sets the status.
     *
     * @param value status
     */
    public void setStatus(RunStatus value) {
        this.status = Objects.requireNonNull(value);
    }

    /**
     * Sets the halt reason and detail.
     *
     * @param reason reason or null
     * @param detail detail or null
     */
    public void setHalt(@Nullable HaltReason reason, @Nullable String detail) {
        this.haltReason = reason;
        this.haltDetail = detail;
    }

    /**
     * Increments the plan version.
     *
     * @return the new plan version
     */
    public int incrementPlanVersion() {
        return ++planVersion;
    }

    /**
     * Sets the plan version (rollback restore).
     *
     * @param value plan version
     */
    public void setPlanVersion(int value) {
        this.planVersion = value;
    }

    /**
     * Sets the start time if not set yet.
     *
     * @param at time
     */
    public void markStarted(Instant at) {
        if (startedAt == null) {
            startedAt = at;
        }
    }

    /**
     * Sets the end time.
     *
     * @param at time or null
     */
    public void setEndedAt(@Nullable Instant at) {
        this.endedAt = at;
    }

    /**
     * Sets the last mutation time.
     *
     * @param at time
     */
    public void touch(Instant at) {
        this.updatedAt = at;
    }

    /**
     * Appends an artifact to the history (does not accept it).
     *
     * @param artifact the artifact
     */
    public void addArtifact(Artifact artifact) {
        artifacts.add(artifact);
    }

    /**
     * Accepts an artifact version for a stage.
     *
     * @param stageId stage id
     * @param version version
     */
    public void accept(String stageId, int version) {
        acceptedVersions.put(stageId, version);
    }

    /**
     * Removes the accepted version of a stage, or restores a previous one.
     *
     * @param stageId stage id
     * @param version previous version or null to remove
     */
    public void restoreAccepted(String stageId, @Nullable Integer version) {
        if (version == null) {
            acceptedVersions.remove(stageId);
        } else {
            acceptedVersions.put(stageId, version);
        }
    }

    /**
     * Replaces all accepted versions (rollback restore).
     *
     * @param versions accepted versions
     */
    public void replaceAcceptedVersions(Map<String, Integer> versions) {
        acceptedVersions.clear();
        for (String stageId : stages.keySet()) {
            Integer v = versions.get(stageId);
            if (v != null) {
                acceptedVersions.put(stageId, v);
            }
        }
    }

    /**
     * Adds an approval.
     *
     * @param approval the approval
     */
    public void addApproval(Approval approval) {
        approvals.add(approval);
    }

    /**
     * Replaces the approval with the same id, and the stage's pending approval when it is that approval.
     *
     * @param approval the updated approval
     */
    public void replaceApproval(Approval approval) {
        for (int i = 0; i < approvals.size(); i++) {
            if (approvals.get(i).id().equals(approval.id())) {
                approvals.set(i, approval);
            }
        }
        StageExecution stage = stages.get(approval.stageId());
        if (stage != null && stage.pendingApproval() != null && stage.pendingApproval().id().equals(approval.id())) {
            stage.setPendingApproval(approval.status() == ApprovalStatus.PENDING ? approval : null);
        }
    }

    /**
     * Sets the clarification.
     *
     * @param value clarification or null
     */
    public void setClarification(@Nullable Clarification value) {
        this.clarification = value;
    }

    /**
     * Adds a checkpoint.
     *
     * @param checkpoint the checkpoint
     */
    public void addCheckpoint(Checkpoint checkpoint) {
        checkpoints.addLast(checkpoint);
    }

    /**
     * Adds a compensation.
     *
     * @param compensation the compensation
     */
    public void addCompensation(Compensation compensation) {
        compensations.add(compensation);
    }

    /**
     * Replaces the compensation with the same id.
     *
     * @param compensation the updated compensation
     */
    public void replaceCompensation(Compensation compensation) {
        for (int i = 0; i < compensations.size(); i++) {
            if (compensations.get(i).id().equals(compensation.id())) {
                compensations.set(i, compensation);
            }
        }
    }

    /**
     * Updates the counters.
     *
     * @param change the change
     */
    public void updateCounters(UnaryOperator<RunCounters> change) {
        this.counters = Objects.requireNonNull(change.apply(counters));
    }

    /**
     * Opens a human-wait interval unless one is open.
     *
     * @param at start time
     * @param reason why the run waits
     */
    public void openHumanWait(Instant at, String reason) {
        if (humanWaitIntervals.isEmpty() || !humanWaitIntervals.getLast().open()) {
            humanWaitIntervals.add(new Interval(at, null, reason));
        }
    }

    /**
     * Closes the open human-wait interval, if any.
     *
     * @param at end time
     */
    public void closeHumanWait(Instant at) {
        if (!humanWaitIntervals.isEmpty() && humanWaitIntervals.getLast().open()) {
            humanWaitIntervals.set(humanWaitIntervals.size() - 1, humanWaitIntervals.getLast().close(at));
        }
    }

    /**
     * Records a policy finding.
     *
     * @param finding the finding
     */
    public void addPolicyFinding(PolicyFindingRecord finding) {
        policyFindings.add(finding);
    }

    /**
     * Records an injected fault for a stage.
     *
     * @param stageId stage id
     * @return the new injected count
     */
    public int recordFaultInjection(String stageId) {
        return injectedFaults.merge(stageId, 1, Integer::sum);
    }

    /**
     * Records a failed attempt; only the first one is kept.
     *
     * @param at failure time
     * @param stageId failing stage
     */
    public void markFailure(Instant at, String stageId) {
        if (firstFailureAt == null) {
            firstFailureAt = at;
            firstFailureStageId = stageId;
        }
    }

    /**
     * Records recovery when {@code stageId} is the stage of the first failure.
     *
     * @param at recovery time
     * @param stageId succeeded stage
     */
    public void markRecoveredIfFirstFailure(Instant at, String stageId) {
        if (firstFailureAt != null && recoveredAt == null && stageId.equals(firstFailureStageId)) {
            recoveredAt = at;
        }
    }

    /**
     * Returns the next audit sequence number.
     *
     * @return the next sequence number (starting at 1)
     */
    public long nextAuditSeq() {
        return ++auditSeq;
    }
}
