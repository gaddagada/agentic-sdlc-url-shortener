package com.agenticsdlc.orchestrator.app.events;

/**
 * Receives the run changes committed by this node's store. Called on the committing thread right after the commit
 * (the engine still holds the run's lock), so an implementation must be fast, must not block and must not throw.
 */
@FunctionalInterface
public interface RunCommitListener {

    /** A listener that ignores every commit. */
    RunCommitListener NONE = committed -> {
    };

    /**
     * Records a committed change.
     *
     * @param committed the change
     */
    void committed(RunCommitted committed);
}
