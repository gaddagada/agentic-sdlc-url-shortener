package com.agenticsdlc.orchestrator.app.events;

import java.util.List;
import java.util.Objects;

import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.model.RunSnapshot;

/**
 * A change of a run that this node's store has committed: the committed snapshot, the audit events committed with it
 * (in sequence order; empty for a save without events) and the run's list row as written to the {@code runs} table.
 * Published only after the transaction committed, so a listener never sees state that could still roll back.
 *
 * @param item the list row of the run after the commit
 * @param snapshot the committed snapshot (immutable)
 * @param events the audit events committed with it, in sequence order
 */
public record RunCommitted(RunListItem item, RunSnapshot snapshot, List<AuditEvent> events) {

    /**
     * Validates and copies the events.
     */
    public RunCommitted {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(snapshot, "snapshot");
        events = List.copyOf(events);
    }

    /**
     * Returns the run id.
     *
     * @return the id
     */
    public String runId() {
        return snapshot.id();
    }
}
