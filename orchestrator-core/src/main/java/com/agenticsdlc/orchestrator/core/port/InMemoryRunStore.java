package com.agenticsdlc.orchestrator.core.port;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Thread-safe in-memory {@link RunStore} (tests and default wiring). Stores deep copies. Its
 * {@link #commit(Run, List, AuditSink)} is atomic as long as the audit sink appends a batch all or nothing (as
 * {@link com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink} does): when the append fails, the previously
 * stored state of the run is restored.
 */
public final class InMemoryRunStore implements RunStore {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /**
     * Creates an empty store.
     */
    public InMemoryRunStore() {
        // empty
    }

    @Override
    public void save(Run run) {
        runs.put(run.id(), run.copy());
    }

    /**
     * Saves the run and appends the events; when the append fails, the run is restored to its previously stored
     * state (or removed when it was new) before the exception is rethrown, so neither the change nor the events
     * remain.
     *
     * @param run the run
     * @param events the events of the mutation
     * @param auditSink the audit sink
     */
    @Override
    public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
        Run copy = run.copy();
        Run previous = runs.put(run.id(), copy);
        try {
            auditSink.append(events);
        } catch (RuntimeException e) {
            if (previous == null) {
                runs.remove(run.id(), copy);
            } else {
                runs.replace(run.id(), copy, previous);
            }
            throw e;
        }
    }

    @Override
    public Optional<Run> load(String id) {
        Run run = runs.get(id);
        return run == null ? Optional.empty() : Optional.of(run.copy());
    }

    @Override
    public List<Run> loadAll() {
        return runs.values().stream().map(Run::copy).sorted(Comparator.comparing(Run::createdAt)).toList();
    }
}
