package com.agenticsdlc.orchestrator.core.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link AuditSink} (tests and default wiring).
 */
public final class InMemoryAuditSink implements AuditSink {

    private final Map<String, List<AuditEvent>> eventsByRun = new ConcurrentHashMap<>();

    /**
     * Creates an empty sink.
     */
    public InMemoryAuditSink() {
        // empty
    }

    @Override
    public void append(List<AuditEvent> events) {
        for (AuditEvent event : events) {
            List<AuditEvent> list = eventsByRun.computeIfAbsent(event.runId(), k -> new ArrayList<>());
            synchronized (list) {
                list.add(event);
            }
        }
    }

    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq) {
        List<AuditEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return list.stream().filter(e -> e.seq() > afterSeq).toList();
        }
    }

    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<AuditEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return list.stream().filter(e -> e.seq() > afterSeq).limit(limit).toList();
        }
    }

    /**
     * Returns all events of a run whose type is {@code type}.
     *
     * @param runId run id
     * @param type event type
     * @return matching events
     */
    public List<AuditEvent> ofType(String runId, AuditEventType type) {
        return forRun(runId, 0).stream().filter(e -> e.type() == type).toList();
    }
}
