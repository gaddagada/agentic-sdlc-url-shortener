package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Immutable, JSON-serializable mirror of the {@link Run} aggregate (records only). This is the persistence
 * format: the app stores it as one JSON document via {@code JsonCodec}. Artifact payloads are stored as
 * kind plus canonical JSON so that they round-trip generically.
 *
 * @param id run id
 * @param workflowId workflow id
 * @param workflowVersion workflow version
 * @param scenarioKey scenario key
 * @param requirement original requirement
 * @param activeRequirement active requirement (after re-planning)
 * @param options run options
 * @param budget effective budget
 * @param status run status
 * @param haltReason halt reason when safe-stopped
 * @param haltDetail halt or abort detail
 * @param planVersion plan version
 * @param createdAt creation time
 * @param startedAt first start time
 * @param endedAt completion or abort time
 * @param updatedAt last mutation time
 * @param stages stage executions in definition order
 * @param artifacts full artifact history
 * @param acceptedVersions stage id to accepted artifact version
 * @param approvals all approvals (history)
 * @param clarification clarification, if any
 * @param checkpoints checkpoints, oldest first
 * @param compensations registered compensations, oldest first
 * @param counters counters
 * @param humanWaitIntervals human-wait intervals
 * @param policyFindings recorded policy findings
 * @param injectedFaults injected fault count per stage
 * @param firstFailureAt first failed attempt time
 * @param firstFailureStageId stage of the first failed attempt
 * @param recoveredAt time the first failed stage later succeeded
 * @param auditSeq last audit sequence number used
 */
public record RunSnapshot(
        String id,
        String workflowId,
        int workflowVersion,
        String scenarioKey,
        String requirement,
        String activeRequirement,
        RunOptions options,
        RunBudget budget,
        RunStatus status,
        @Nullable HaltReason haltReason,
        @Nullable String haltDetail,
        int planVersion,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        Instant updatedAt,
        List<StageSnapshot> stages,
        List<ArtifactSnapshot> artifacts,
        Map<String, Integer> acceptedVersions,
        List<Approval> approvals,
        @Nullable Clarification clarification,
        List<Checkpoint> checkpoints,
        List<Compensation> compensations,
        RunCounters counters,
        List<Interval> humanWaitIntervals,
        List<PolicyFindingRecord> policyFindings,
        Map<String, Integer> injectedFaults,
        @Nullable Instant firstFailureAt,
        @Nullable String firstFailureStageId,
        @Nullable Instant recoveredAt,
        long auditSeq) {

    /**
     * Normalizes collections.
     */
    public RunSnapshot {
        stages = Immutables.list(stages);
        artifacts = Immutables.list(artifacts);
        acceptedVersions = Immutables.map(acceptedVersions);
        approvals = Immutables.list(approvals);
        checkpoints = Immutables.list(checkpoints);
        compensations = Immutables.list(compensations);
        humanWaitIntervals = Immutables.list(humanWaitIntervals);
        policyFindings = Immutables.list(policyFindings);
        injectedFaults = Immutables.map(injectedFaults);
        if (counters == null) {
            counters = RunCounters.ZERO;
        }
    }

    /**
     * Snapshot of a stage execution.
     *
     * @param stageId stage id
     * @param status status
     * @param attempts attempts
     * @param activeAgent active agent id
     * @param startedAt latest attempt start
     * @param endedAt latest attempt end
     * @param lastError latest error
     * @param feedback feedback entries
     * @param nextAttemptAt scheduled retry time
     * @param pendingApproval pending approval
     */
    public record StageSnapshot(String stageId, StageStatus status, int attempts, @Nullable String activeAgent,
                                @Nullable Instant startedAt, @Nullable Instant endedAt, @Nullable String lastError,
                                List<String> feedback, @Nullable Instant nextAttemptAt,
                                @Nullable Approval pendingApproval) {
        /**
         * Normalizes collections.
         */
        public StageSnapshot {
            feedback = Immutables.list(feedback);
        }
    }

    /**
     * Snapshot of an artifact with its payload stored as canonical JSON.
     *
     * @param id artifact id
     * @param runId run id
     * @param stageId stage id
     * @param kind kind (selects the payload class when parsing)
     * @param version version
     * @param planVersion plan version
     * @param hash payload hash
     * @param payloadJson canonical payload JSON
     * @param provenance provenance
     * @param createdAt creation time
     */
    public record ArtifactSnapshot(String id, String runId, String stageId, ArtifactKind kind, int version,
                                   int planVersion, String hash, String payloadJson, Provenance provenance,
                                   Instant createdAt) {
    }
}
