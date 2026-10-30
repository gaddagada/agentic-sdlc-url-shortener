package com.agenticsdlc.orchestrator.app.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.jspecify.annotations.Nullable;

/**
 * A persisted run: the aggregate as one JSON snapshot plus a few queryable columns. {@link #version} is the JPA
 * optimistic-lock version, so two writers can never silently overwrite each other's snapshot.
 *
 * <p>The listing columns added by V2 ({@code autonomy}, {@code workspace_mode}, {@code started_by},
 * {@code risk_level}, {@code node_id}, {@code audit_head_hash}) are copies of snapshot data for paged listings and
 * filters; they are NULL for rows written before V2 until the run is saved again. The progress columns added by V3
 * ({@code pending_actions}, {@code stages_succeeded}, {@code stages_total}) likewise feed the paged runs list without
 * reading the snapshot; they are NULL for rows written before V3 until the run is saved again.</p>
 */
@Entity
@Table(name = "runs")
public class RunEntity {

    /** Maximum length of the {@code requirement} column. */
    static final int REQUIREMENT_MAX = 4000;

    /** Maximum length of the {@code started_by} and {@code node_id} columns. */
    static final int LONG_ID_MAX = 128;

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "scenario", length = 64, nullable = false)
    private String scenario;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "halt_reason", length = 64)
    private @Nullable String haltReason;

    @Column(name = "requirement", length = REQUIREMENT_MAX, nullable = false)
    private String requirement;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    @Column(name = "snapshot_json", nullable = false)
    private String snapshotJson;

    @Version
    @Column(name = "version", nullable = false)
    private @Nullable Long version;

    @Column(name = "autonomy", length = 16)
    private @Nullable String autonomy;

    @Column(name = "workspace_mode", length = 16)
    private @Nullable String workspaceMode;

    @Column(name = "started_by", length = LONG_ID_MAX)
    private @Nullable String startedBy;

    @Column(name = "risk_level", length = 16)
    private @Nullable String riskLevel;

    @Column(name = "node_id", length = LONG_ID_MAX)
    private @Nullable String nodeId;

    @Column(name = "audit_head_hash", length = 64)
    private @Nullable String auditHeadHash;

    @Column(name = "pending_actions")
    private @Nullable Integer pendingActions;

    @Column(name = "stages_succeeded")
    private @Nullable Integer stagesSucceeded;

    @Column(name = "stages_total")
    private @Nullable Integer stagesTotal;

    /** For JPA. */
    protected RunEntity() {
        this.id = "";
        this.scenario = "";
        this.status = "";
        this.requirement = "";
        this.createdAt = Instant.EPOCH;
        this.updatedAt = Instant.EPOCH;
        this.snapshotJson = "";
    }

    /**
     * Creates a new (not yet persisted) row.
     *
     * @param id run id
     * @param scenario scenario key
     * @param createdAt creation time
     */
    RunEntity(String id, String scenario, Instant createdAt) {
        this();
        this.id = id;
        this.scenario = scenario;
        this.createdAt = createdAt;
    }

    /**
     * Copies the mutable state of a run into this row.
     *
     * @param newStatus status name
     * @param newHaltReason halt reason name or null
     * @param newRequirement original requirement (truncated to the column length)
     * @param newPlanVersion plan version
     * @param newUpdatedAt last update
     * @param newEndedAt end time or null
     * @param newSnapshotJson snapshot JSON
     */
    void update(String newStatus, @Nullable String newHaltReason, String newRequirement, int newPlanVersion,
                Instant newUpdatedAt, @Nullable Instant newEndedAt, String newSnapshotJson) {
        this.status = newStatus;
        this.haltReason = newHaltReason;
        this.requirement = newRequirement.length() > REQUIREMENT_MAX
                ? newRequirement.substring(0, REQUIREMENT_MAX) : newRequirement;
        this.planVersion = newPlanVersion;
        this.updatedAt = newUpdatedAt;
        this.endedAt = newEndedAt;
        this.snapshotJson = newSnapshotJson;
    }

    /**
     * Copies the listing columns of a run into this row. A null {@code newStartedBy} or {@code newNodeId} keeps the
     * stored value (the starter is only known from the run's first commit, the node from commits that start work).
     *
     * @param newAutonomy autonomy level name
     * @param newWorkspaceMode workspace mode name
     * @param newStartedBy id of the operator who started the run, or null to keep the stored value
     * @param newRiskLevel current risk level name, or null when it could not be computed
     * @param newNodeId id of the engine node that last started work on the run, or null to keep the stored value
     * @param newAuditHeadHash hash of the run's last audit event, or null when the run has no signed events
     */
    void describe(String newAutonomy, String newWorkspaceMode, @Nullable String newStartedBy,
                  @Nullable String newRiskLevel, @Nullable String newNodeId, @Nullable String newAuditHeadHash) {
        this.autonomy = newAutonomy;
        this.workspaceMode = newWorkspaceMode;
        if (newStartedBy != null) {
            this.startedBy = clip(newStartedBy);
        }
        this.riskLevel = newRiskLevel;
        if (newNodeId != null) {
            this.nodeId = clip(newNodeId);
        }
        this.auditHeadHash = newAuditHeadHash;
    }

    /**
     * Copies the progress columns of a run into this row.
     *
     * @param newPendingActions stages waiting for a human
     * @param newStagesSucceeded succeeded stages
     * @param newStagesTotal stages of the workflow
     */
    void progress(int newPendingActions, int newStagesSucceeded, int newStagesTotal) {
        this.pendingActions = newPendingActions;
        this.stagesSucceeded = newStagesSucceeded;
        this.stagesTotal = newStagesTotal;
    }

    private static String clip(String value) {
        return value.length() > LONG_ID_MAX ? value.substring(0, LONG_ID_MAX) : value;
    }

    /** @return the run id */
    public String getId() {
        return id;
    }

    /** @return the scenario key */
    public String getScenario() {
        return scenario;
    }

    /** @return the status name */
    public String getStatus() {
        return status;
    }

    /** @return the halt reason name, or null */
    public @Nullable String getHaltReason() {
        return haltReason;
    }

    /** @return the original requirement (possibly truncated) */
    public String getRequirement() {
        return requirement;
    }

    /** @return the plan version */
    public int getPlanVersion() {
        return planVersion;
    }

    /** @return the creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the last update time */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @return the end time, or null */
    public @Nullable Instant getEndedAt() {
        return endedAt;
    }

    /** @return the snapshot JSON */
    public String getSnapshotJson() {
        return snapshotJson;
    }

    /** @return the optimistic-lock version, or null before the first insert */
    public @Nullable Long getVersion() {
        return version;
    }

    /** @return the autonomy level name, or null for rows written before V2 */
    public @Nullable String getAutonomy() {
        return autonomy;
    }

    /** @return the workspace mode name, or null for rows written before V2 */
    public @Nullable String getWorkspaceMode() {
        return workspaceMode;
    }

    /** @return the id of the operator who started the run, or null when unknown */
    public @Nullable String getStartedBy() {
        return startedBy;
    }

    /** @return the run's current risk level name, or null when unknown */
    public @Nullable String getRiskLevel() {
        return riskLevel;
    }

    /** @return the id of the engine node that last started work on the run, or null when unknown */
    public @Nullable String getNodeId() {
        return nodeId;
    }

    /** @return the hash of the run's last audit event, or null when the run has no signed events */
    public @Nullable String getAuditHeadHash() {
        return auditHeadHash;
    }

    /** @return stages waiting for a human after the last save, or null for rows written before V3 */
    public @Nullable Integer getPendingActions() {
        return pendingActions;
    }

    /** @return succeeded stages after the last save, or null for rows written before V3 */
    public @Nullable Integer getStagesSucceeded() {
        return stagesSucceeded;
    }

    /** @return stages of the workflow, or null for rows written before V3 */
    public @Nullable Integer getStagesTotal() {
        return stagesTotal;
    }

}
