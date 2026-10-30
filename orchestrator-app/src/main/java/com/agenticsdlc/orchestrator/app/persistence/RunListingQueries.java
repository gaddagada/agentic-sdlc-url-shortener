package com.agenticsdlc.orchestrator.app.persistence;

import java.util.Optional;

import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.app.runs.RunListPage;
import com.agenticsdlc.orchestrator.app.runs.RunListQuery;

/**
 * Projection queries of the runs list, a fragment of {@link RunRepository}: they select the listing columns of the
 * {@code runs} table into {@link RunListItem}s (the requirement cut to {@link RunListItem#REQUIREMENT_PROBE}
 * characters by the database) and never read {@code snapshot_json}.
 */
public interface RunListingQueries {

    /**
     * Returns one page of runs matching the query's filters, in the query's order (ties broken by id).
     *
     * @param query page, filters and order
     * @return the page with the total number of matching rows
     */
    RunListPage findListing(RunListQuery query);

    /**
     * Returns the list row of one run.
     *
     * @param runId run id
     * @return the row, or empty
     */
    Optional<RunListItem> findListItem(String runId);
}
