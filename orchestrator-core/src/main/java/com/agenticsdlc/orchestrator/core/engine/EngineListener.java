package com.agenticsdlc.orchestrator.core.engine;

import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Optional callback notified after every committed run mutation (outside the run lock), for example to
 * push updates to clients. Implementations must be fast and must not throw; exceptions are logged.
 */
@FunctionalInterface
public interface EngineListener {

    /**
     * Called after a run changed.
     *
     * @param run a snapshot copy of the run
     */
    void onRunChanged(Run run);
}
