package com.agenticsdlc.orchestrator.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * An append-only audit event. Sequence numbers are per run, strictly increasing and gap-free.
 *
 * <p>Events written by the engine are hash-chained per run (see {@link AuditChain}): {@code prevHash} is the
 * {@code hash} of the previous event of the run ({@link AuditChain#GENESIS} for the first one) and {@code hash} is
 * the SHA-256 of the event's canonical form including {@code prevHash}. Both are null for events created with the
 * eight-argument constructor (legacy, unsigned events).</p>
 *
 * @param seq per-run sequence number (starting at 1)
 * @param timestamp event time
 * @param runId run id
 * @param stageId stage id, or null for run-level events
 * @param type event type
 * @param actor actor
 * @param message human-readable message
 * @param data structured data (JSON-serializable values: strings, numbers, booleans, lists, maps)
 * @param prevHash hash of the previous event of the run (lowercase hex SHA-256, {@link AuditChain#GENESIS} for the
 *                 first event), or null for an unsigned event
 * @param hash lowercase hex SHA-256 of this event ({@link AuditChain#hashOf(AuditEvent)}), or null for an unsigned
 *             event
 */
public record AuditEvent(long seq, Instant timestamp, String runId, @Nullable String stageId, AuditEventType type,
                         Actor actor, String message, Map<String, Object> data, @Nullable String prevHash,
                         @Nullable String hash) {

    /**
     * Maximum length of a message written by the engine: longer messages are abbreviated before the event is
     * hashed, so stores with a bounded message column (2000 characters in the reference schema) keep messages
     * verbatim and the hash chain verifiable.
     */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    /**
     * Validates and copies fields.
     */
    public AuditEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actor, "actor");
        message = Immutables.text(message);
        data = Immutables.map(data);
    }

    /**
     * Creates an unsigned event (no {@code prevHash} and no {@code hash}); the constructor before hash chaining
     * existed.
     *
     * @param seq per-run sequence number (starting at 1)
     * @param timestamp event time
     * @param runId run id
     * @param stageId stage id, or null for run-level events
     * @param type event type
     * @param actor actor
     * @param message human-readable message
     * @param data structured data
     */
    public AuditEvent(long seq, Instant timestamp, String runId, @Nullable String stageId, AuditEventType type,
                      Actor actor, String message, Map<String, Object> data) {
        this(seq, timestamp, runId, stageId, type, actor, message, data, null, null);
    }

    /**
     * Returns a copy with the given chain fields.
     *
     * @param newPrevHash the previous event's hash, or null
     * @param newHash this event's hash, or null
     * @return the copy
     */
    public AuditEvent withChain(@Nullable String newPrevHash, @Nullable String newHash) {
        return new AuditEvent(seq, timestamp, runId, stageId, type, actor, message, data, newPrevHash, newHash);
    }

    /**
     * Returns whether the event carries both chain fields.
     *
     * @return true when {@code prevHash} and {@code hash} are present
     */
    public boolean signed() {
        return prevHash != null && hash != null;
    }
}
