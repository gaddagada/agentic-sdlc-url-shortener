package com.agenticsdlc.orchestrator.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * An append-only audit event. Sequence numbers are per run, strictly increasing and gap-free.
 *
 * @param seq per-run sequence number (starting at 1)
 * @param timestamp event time
 * @param runId run id
 * @param stageId stage id, or null for run-level events
 * @param type event type
 * @param actor actor
 * @param message human-readable message
 * @param data structured data (JSON-serializable values: strings, numbers, booleans, lists, maps)
 */
public record AuditEvent(long seq, Instant timestamp, String runId, @Nullable String stageId, AuditEventType type,
                         Actor actor, String message, Map<String, Object> data) {

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
}
