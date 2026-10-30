package com.agenticsdlc.orchestrator.app.runs;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One row of the runs list: the queryable columns of the {@code runs} table, never the run snapshot. The same item is
 * published after every committed change of a run (the {@code run-summary} server-sent event), so the list and the
 * live updates have one shape.
 *
 * <p>Columns added after a run was last saved are null for that row until the run is saved again: the V2 listing
 * columns ({@code autonomy} ... {@code nodeId}) and the V3 progress columns ({@code pendingActions},
 * {@code stagesSucceeded}, {@code stagesTotal}).</p>
 *
 * @param id run id
 * @param scenario scenario key
 * @param status run status name
 * @param haltReason halt reason name, or null
 * @param requirement original requirement as stored in the list column (list queries return at most
 *                    {@link #REQUIREMENT_PROBE} characters; views truncate it further)
 * @param planVersion plan version
 * @param createdAt creation time
 * @param updatedAt last update
 * @param endedAt end time, or null
 * @param autonomy autonomy level name, or null
 * @param workspaceMode workspace mode name, or null
 * @param startedBy id of the operator who started the run, or null
 * @param riskLevel current risk level name, or null
 * @param nodeId id of the engine node that last started work on the run, or null
 * @param pendingActions stages waiting for a human (approval or clarification), or null
 * @param stagesSucceeded succeeded stages, or null
 * @param stagesTotal stages of the workflow, or null
 */
public record RunListItem(String id, String scenario, String status, @Nullable String haltReason, String requirement,
                          int planVersion, Instant createdAt, Instant updatedAt, @Nullable Instant endedAt,
                          @Nullable String autonomy, @Nullable String workspaceMode, @Nullable String startedBy,
                          @Nullable String riskLevel, @Nullable String nodeId, @Nullable Integer pendingActions,
                          @Nullable Integer stagesSucceeded, @Nullable Integer stagesTotal) {

    /**
     * Characters of the requirement column read by list queries: one more than the longest requirement a list item
     * shows, so a view can tell whether it was truncated.
     */
    public static final int REQUIREMENT_PROBE = 201;

    /**
     * Validates the required columns.
     */
    public RunListItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
