package com.agenticsdlc.orchestrator.app.runs;

import java.util.Optional;

/**
 * Port of the paged runs list, read from the listing columns of the {@code runs} table: cheap, because no run snapshot
 * is read or deserialized. It lists what the store holds, which includes runs evicted from the engine's memory and the
 * runs of other engine nodes; a change of a run is visible once it is committed.
 */
public interface RunListing {

    /**
     * Returns one page of runs.
     *
     * @param query page, filters and order
     * @return the page
     */
    RunListPage page(RunListQuery query);

    /**
     * Returns the list row of one run.
     *
     * @param runId run id
     * @return the row, or empty when the store has no such run
     */
    Optional<RunListItem> find(String runId);
}
