package com.agenticsdlc.orchestrator.core.engine;

/**
 * Thrown when a command (or an internal event) would mutate a run whose lease is held by another engine node, which
 * is executing the run's stages right now. Reads never need a lease. The command can be retried once the run waits
 * for a human or has finished on the other node (its lease is then released), or after the other node's lease
 * expired (for example because that node died).
 *
 * <p>It is an {@link InvalidRunStateException} (a conflict with the run's current state), so adapters that map
 * that type to HTTP 409 already handle it; they may map it explicitly to expose {@link #holder()}.</p>
 */
public final class RunLeasedElsewhereException extends InvalidRunStateException {

    private static final long serialVersionUID = 1L;

    private final String runId;
    private final String holder;

    /**
     * Creates the exception.
     *
     * @param runId the run
     * @param holder id of the node holding the run's lease ({@code unknown} when the lease vanished meanwhile)
     */
    public RunLeasedElsewhereException(String runId, String holder) {
        super("run " + runId + " is being executed by engine node " + holder
                + "; retry when it waits for a human or has finished");
        this.runId = runId;
        this.holder = holder;
    }

    /**
     * Returns the run id.
     *
     * @return run id
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the id of the node holding the run's lease.
     *
     * @return node id
     */
    public String holder() {
        return holder;
    }
}
