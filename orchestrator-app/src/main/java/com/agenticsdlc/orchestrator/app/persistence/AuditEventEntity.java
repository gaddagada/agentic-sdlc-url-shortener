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
 *
 * <p>Events written by the engine are hash-chained per run: {@code prev_hash} and {@code hash} hold the chain links
 * (both NULL for legacy, unsigned events written before the chain existed). Every hashed field is stored verbatim:
 * the timestamp column keeps microseconds (the engine's instants have millisecond precision), the message column
 * holds the engine's maximum message length and actor ids are far shorter than their column.</p>
 */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    /** Maximum length of the {@code message} column (the engine's maximum message length). */
    static final int MESSAGE_MAX = 2000;

    /** Maximum length of the {@code actor_id} column. */
    static final int ACTOR_ID_MAX = 128;

    /** Length of the {@code prev_hash} and {@code hash} columns (lowercase hex SHA-256). */
    static final int HASH_LENGTH = 64;

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

    @Column(name = "prev_hash", length = HASH_LENGTH)
    private @Nullable String prevHash;

    @Column(name = "hash", length = HASH_LENGTH)
    private @Nullable String hash;

    /** For JPA. */
    protected AuditEventEntity() {
        this("", 0L, Instant.EPOCH, null, "", "", "", "", null, null, null);
    }

    /**
     * Creates a new row. Over-long message and actor id values of unsigned (legacy) events are truncated to the
     * column lengths; a hash-chained event is stored verbatim, because truncating it would break the chain.
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
     * @param prevHash hash of the previous event of the run, or null for an unsigned event
     * @param hash hash of this event, or null for an unsigned event
     * @throws IllegalArgumentException when a hash-chained event does not fit its columns or its timestamp is more
 *                                  precise than the column (microseconds)

     */
    AuditEventEntity(String runId, long seq, Instant timestamp, @Nullable String stageId, String type,
                     String actorType, String actorId, String message, @Nullable String dataJson,
                     @Nullable String prevHash, @Nullable String hash) {
        boolean chained = prevHash != null || hash != null;
        if (chained && (message.length() > MESSAGE_MAX || actorId.length() > ACTOR_ID_MAX)) {
            throw new IllegalArgumentException("hash-chained audit event seq " + seq + " of run " + runId
                    + " does not fit the audit_events columns (message " + message.length() + "/" + MESSAGE_MAX
                    + ", actor id " + actorId.length() + "/" + ACTOR_ID_MAX + "); truncating it would break the chain");
        }
        if (chained && timestamp.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("hash-chained audit event seq " + seq + " of run " + runId
                    + " has a sub-microsecond timestamp that the ts column (microseconds) would round, which would "
                    + "break the chain");
        }
        this.runId = runId;
        this.seq = seq;
        this.timestamp = timestamp;
        this.stageId = stageId;
        this.type = type;
        this.actorType = actorType;
        this.actorId = truncate(actorId, ACTOR_ID_MAX);
        this.message = truncate(message, MESSAGE_MAX);
        this.dataJson = dataJson;
        this.prevHash = prevHash;
        this.hash = hash;
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

    /** @return the hash of the previous event of the run, or null for an unsigned event */
    public @Nullable String getPrevHash() {
        return prevHash;
    }

    /** @return the hash of this event, or null for an unsigned event */
    public @Nullable String getHash() {
        return hash;
    }
}
