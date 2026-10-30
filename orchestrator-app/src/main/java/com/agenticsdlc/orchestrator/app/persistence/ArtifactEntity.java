package com.agenticsdlc.orchestrator.app.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * One artifact version produced by a stage. Rows are immutable and inserted once (ids are assigned by the engine,
 * so the entity implements {@link Persistable} to make Spring Data insert instead of merge).
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity implements Persistable<String> {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    @Column(name = "stage_id", length = 64, nullable = false)
    private String stageId;

    @Column(name = "kind", length = 64, nullable = false)
    private String kind;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    @Column(name = "hash", length = 64, nullable = false)
    private String hash;

    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(name = "provenance_json", nullable = false)
    private String provenanceJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh = true;

    /** For JPA. */
    protected ArtifactEntity() {
        this("", "", "", "", 0, 0, "", "", "", Instant.EPOCH);
    }

    /**
     * Creates a new row.
     *
     * @param id artifact id
     * @param runId run id
     * @param stageId stage id
     * @param kind artifact kind name
     * @param version artifact version
     * @param planVersion plan version the artifact was produced under
     * @param hash SHA-256 of the canonical payload JSON
     * @param payloadJson canonical payload JSON
     * @param provenanceJson provenance JSON
     * @param createdAt creation time
     */
    ArtifactEntity(String id, String runId, String stageId, String kind, int version, int planVersion, String hash,
                   String payloadJson, String provenanceJson, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.stageId = stageId;
        this.kind = kind;
        this.version = version;
        this.planVersion = planVersion;
        this.hash = hash;
        this.payloadJson = payloadJson;
        this.provenanceJson = provenanceJson;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.fresh = false;
    }

    /** @return the run id */
    public String getRunId() {
        return runId;
    }

    /** @return the stage id */
    public String getStageId() {
        return stageId;
    }

    /** @return the artifact kind name */
    public String getKind() {
        return kind;
    }

    /** @return the artifact version */
    public int getVersion() {
        return version;
    }

    /** @return the plan version */
    public int getPlanVersion() {
        return planVersion;
    }

    /** @return the payload hash */
    public String getHash() {
        return hash;
    }

    /** @return the canonical payload JSON */
    public String getPayloadJson() {
        return payloadJson;
    }

    /** @return the provenance JSON */
    public String getProvenanceJson() {
        return provenanceJson;
    }

    /** @return the creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
