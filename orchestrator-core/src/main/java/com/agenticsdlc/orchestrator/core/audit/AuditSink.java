package com.agenticsdlc.orchestrator.core.audit;

import java.util.List;

/**
 * Append-only audit log port. The engine appends the events of each command in one batch while holding
 * the run lock, after persisting the run (durability before visibility).
 */
public interface AuditSink {

    /**
     * Appends events (all of the same run, in sequence order).
     *
     * @param events events to append
     */
    void append(List<AuditEvent> events);

    /**
     * Returns the events of a run with {@code seq > afterSeq}, in sequence order.
     *
     * @param runId run id
     * @param afterSeq exclusive lower bound (0 for all)
     * @return events
     */
    List<AuditEvent> forRun(String runId, long afterSeq);

    /**
     * Returns at most {@code limit} events of a run with {@code seq > afterSeq}, in sequence order (a page:
     * continue with the {@code seq} of the last returned event). The default implementation truncates
     * {@link #forRun(String, long)}; stores should override it with a bounded query.
     *
     * @param runId run id
     * @param afterSeq exclusive lower bound (0 for all)
     * @param limit maximum number of events (positive)
     * @return at most {@code limit} events
     * @throws IllegalArgumentException when {@code limit} is not positive
     */
    default List<AuditEvent> forRun(String runId, long afterSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<AuditEvent> all = forRun(runId, afterSeq);
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }
}
