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
     * Persists the state of a run after a mutation together with the audit events the mutation produced. The
     * default implementation calls {@link #save(Run)} and then {@link AuditSink#append(List)}, which is not
     * atomic: when the append fails the run is saved without its events. A transactional store (for example a
     * JPA store sharing a transaction manager with a JPA audit sink) should override it to persist both in one
     * transaction, so the run state and the audit trail can never diverge.
     *
     * <p>When this method throws, the engine propagates the exception to the caller of the command. The engine's
     * in-memory run keeps the mutation, so it may be ahead of the store until the next successful commit, which
     * persists the complete current state again (events of the failed commit are not re-sent).</p>
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
