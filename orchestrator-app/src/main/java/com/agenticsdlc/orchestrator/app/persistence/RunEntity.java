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
 */
@Entity
@Table(name = "runs")
public class RunEntity {

    /** Maximum length of the {@code requirement} column. */
    static final int REQUIREMENT_MAX = 4000;

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
}
