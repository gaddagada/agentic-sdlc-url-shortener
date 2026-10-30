package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.audit.AuditEvent;

/**
 * One audit event.
 *
 * @param seq per-run sequence number
 * @param timestamp event time
 * @param stageId stage, or null for run-level events
 * @param type event type
 * @param actor who caused the event
 * @param message human-readable message
 * @param data structured event data
 */
public record AuditEventView(long seq, Instant timestamp, @Nullable String stageId, String type, ActorView actor,
                             String message, Map<String, Object> data) {

    /**
     * Maps a core event.
     *
     * @param event the event
     * @return the view
     */
    public static AuditEventView from(AuditEvent event) {
        return new AuditEventView(event.seq(), event.timestamp(), event.stageId(), event.type().name(),
                new ActorView(event.actor().type().name(), event.actor().id()), event.message(), event.data());
    }

    /**
     * The actor of an event.
     *
     * @param type SYSTEM, AGENT or HUMAN
     * @param id actor id (operator id for humans)
     */
    public record ActorView(String type, String id) {
    }
}
