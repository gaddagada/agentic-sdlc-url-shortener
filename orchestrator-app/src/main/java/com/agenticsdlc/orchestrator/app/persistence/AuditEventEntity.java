package com.agenticsdlc.orchestrator.app.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.jspecify.annotations.Nullable;

/**
 * One row of the append-only audit trail. Rows are never updated or deleted by the application.
 */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    /** Maximum length of the {@code message} column. */
    static final int MESSAGE_MAX = 2000;

    /** Maximum length of the {@code actor_id} column. */
    static final int ACTOR_ID_MAX = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private @Nullable Long id;

    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "ts", nullable = false)
    private Instant timestamp;

    @Column(name = "stage_id", length = 64)
    private @Nullable String stageId;

    @Column(name = "type", length = 64, nullable = false)
    private String type;

    @Column(name = "actor_type", length = 16, nullable = false)
    private String actorType;

    @Column(name = "actor_id", length = ACTOR_ID_MAX, nullable = false)
    private String actorId;

    @Column(name = "message", length = MESSAGE_MAX, nullable = false)
    private String message;

    @Column(name = "data_json")
    private @Nullable String dataJson;

    /** For JPA. */
    protected AuditEventEntity() {
        this("", 0L, Instant.EPOCH, null, "", "", "", "", null);
    }

    /**
     * Creates a new row; over-long message and actor id values are truncated to the column lengths.
     *
     * @param runId run id
     * @param seq per-run sequence number
     * @param timestamp event time
     * @param stageId stage id or null
     * @param type event type name
     * @param actorType actor type name
     * @param actorId actor id
     * @param message message
     * @param dataJson event data JSON or null
     */
    AuditEventEntity(String runId, long seq, Instant timestamp, @Nullable String stageId, String type,
                     String actorType, String actorId, String message, @Nullable String dataJson) {
        this.runId = runId;
        this.seq = seq;
        this.timestamp = timestamp;
        this.stageId = stageId;
        this.type = type;
        this.actorType = actorType;
        this.actorId = truncate(actorId, ACTOR_ID_MAX);
        this.message = truncate(message, MESSAGE_MAX);
        this.dataJson = dataJson;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /** @return the generated id, or null before insert */
    public @Nullable Long getId() {
        return id;
    }

    /** @return the run id */
    public String getRunId() {
        return runId;
    }

    /** @return the per-run sequence number */
    public long getSeq() {
        return seq;
    }

    /** @return the event time */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @return the stage id, or null */
    public @Nullable String getStageId() {
        return stageId;
    }

    /** @return the event type name */
    public String getType() {
        return type;
    }

    /** @return the actor type name */
    public String getActorType() {
        return actorType;
    }

    /** @return the actor id */
    public String getActorId() {
        return actorId;
    }

    /** @return the message */
    public String getMessage() {
        return message;
    }

    /** @return the event data JSON, or null */
    public @Nullable String getDataJson() {
        return dataJson;
    }
}
