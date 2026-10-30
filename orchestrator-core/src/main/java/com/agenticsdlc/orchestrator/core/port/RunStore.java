package com.agenticsdlc.orchestrator.core.port;

import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Persistence port for runs. The engine calls {@link #save(Run)} under the run lock after every mutation
 * with the live aggregate; implementations must copy or serialize it before returning (for example
 * {@code codec.toJson(run.toSnapshot())}) and must not keep a reference to it.
 */
public interface RunStore {

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
