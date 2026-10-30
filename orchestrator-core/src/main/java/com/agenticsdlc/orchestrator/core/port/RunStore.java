package com.agenticsdlc.orchestrator.core.port;

import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Persistence port for runs. The engine calls {@link #commit(Run, List, AuditSink)} under the run lock after
 * every mutation with the live aggregate and the audit events of that mutation; implementations must copy or
 * serialize the run before returning (for example {@code codec.toJson(run.toSnapshot())}) and must not keep a
 * reference to it.
 */
public interface RunStore {

    /**
     * Persists the state of a run after a mutation together with the audit events the mutation produced.
     *
     * <p><strong>Durability contract:</strong> the engine guarantees that every persisted state change has its audit
     * events only when this method is atomic: either the run state and all events are persisted, or neither is and
     * the method throws. {@link InMemoryRunStore} (with an all-or-nothing audit sink such as
     * {@link com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink}) and the application's JPA store (one
     * transaction for the run and its events) are atomic.</p>
     *
     * <p>The default implementation is <em>not</em> atomic: it calls {@link #save(Run)} and appends the events with
     * {@link AuditSink#append(List)} only after the save succeeded, and rolls nothing back. When the append fails, the
     * store holds the new run state without the events of that mutation; those events are lost (the engine never
     * re-sends them). The engine then resynchronizes the run's audit chain head with the last event the audit log
     * holds, so the hash chain continues without a gap, but the audit trail does not record that one mutation.
     * Override this method in a store that can persist both in one transaction.</p>
     *
     * <p>When this method throws, the engine reports an {@code InvalidRunStateException} (with this exception as its
     * cause) to the caller of the command and discards the mutation: the run is dropped from the engine's in-memory
     * registry, so the next command or read loads the stored state (whose audit chain head is resynchronized with
     * the audit log). The engine renews the run's lease right before calling this method and never calls it once
     * it lost the lease.</p>
     *
     * @param run the run (live aggregate; do not retain)
     * @param events the audit events of the mutation, in sequence order (may be empty)
     * @param auditSink the engine's audit sink
     */
    default void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
        save(run);
        auditSink.append(events);
    }

    /**
     * Saves (inserts or updates) a run.
     *
     * @param run the run (live aggregate; do not retain)
     */
    void save(Run run);

    /**
     * Loads a run.
     *
     * @param id run id
     * @return the run, a new instance not shared with the store
     */
    Optional<Run> load(String id);

    /**
     * Loads all runs.
     *
     * @return all runs, new instances
     */
    List<Run> loadAll();
}
