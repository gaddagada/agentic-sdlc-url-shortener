package com.agenticsdlc.orchestrator.core.port;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Thread-safe in-memory {@link RunStore} (tests and default wiring). Stores deep copies.
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
