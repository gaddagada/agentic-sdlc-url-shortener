package com.agenticsdlc.shortener.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Stored outcome of a request made with an {@code Idempotency-Key}.
 *
 * <p>A record is first inserted as <em>pending</em> ({@link #PENDING_STATUS}, empty body) to reserve the key, and is
 * completed with the response status and JSON body once the request succeeds. Implements {@link Persistable} so that
 * saving a new record always issues an {@code INSERT}: a concurrent reservation of the same key then fails on the
 * primary key instead of being silently merged into the other request's row.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord implements Persistable<String> {

    /** Status value of a reserved key whose request has not completed yet. */
    public static final int PENDING_STATUS = 0;

    @Id
    @Column(name = "idem_key", length = 128)
    private String idempotencyKey;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "status", nullable = false)
    private int status;

    // Unbounded VARCHAR in the schema (see V1__init.sql); mapped as a plain string on purpose.
    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    /** Required by JPA. */
    protected IdempotencyRecord() {
        this.idempotencyKey = "";
        this.fingerprint = "";
        this.body = "";
        this.createdAt = Instant.EPOCH;
    }

    private IdempotencyRecord(String idempotencyKey, String fingerprint, Instant createdAt) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = PENDING_STATUS;
        this.body = "";
    }

    /**
     * Creates a pending reservation for {@code idempotencyKey}.
     *
     * @param idempotencyKey client supplied key
     * @param fingerprint    SHA-256 hex of the canonical request
     * @param createdAt      reservation instant
     * @return a new, not yet persisted record
     */
    public static IdempotencyRecord pending(String idempotencyKey, String fingerprint, Instant createdAt) {
        return new IdempotencyRecord(idempotencyKey, fingerprint, createdAt);
    }

    /** {@return whether the original request is still in flight} */
    public boolean isPending() {
        return status == PENDING_STATUS;
    }

    /**
     * Tells whether the record is older than {@code cutoff}.
     *
     * @param cutoff records created before this instant are considered old
     * @return {@code true} when {@code createdAt < cutoff}
     */
    public boolean isCreatedBefore(Instant cutoff) {
        return createdAt.isBefore(cutoff);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
