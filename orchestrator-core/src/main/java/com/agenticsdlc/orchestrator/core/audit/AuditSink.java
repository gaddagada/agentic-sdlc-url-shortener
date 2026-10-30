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
}
