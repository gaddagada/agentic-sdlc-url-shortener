package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Compact view of a run for the runs list (an item of {@link RunPageView}, and the data of the {@code run-summary}
 * server-sent event). Built from the listing columns of the {@code runs} table, never from the run snapshot; columns
 * that a run's row does not have yet (rows written before the column existed and not saved since) are null.
 *
 * @param id run id
 * @param scenario scenario key
 * @param status run status
 * @param haltReason halt reason, or null
 * @param requirement original requirement truncated to 200 characters (an ellipsis marks the cut)
 * @param planVersion plan version
 * @param createdAt creation time
 * @param updatedAt last update
 * @param endedAt end time, or null
 * @param autonomy autonomy level, or null
 * @param workspace workspace mode, or null
 * @param startedBy operator who started the run, or null
 * @param riskLevel current risk level, or null
 * @param nodeId engine node that last started work on the run (in a multi-node deployment the console polls runs of
 *               other nodes), or null
 * @param pendingActions number of pending human actions, or null
 * @param progress succeeded and total stages, or null
 */
public record RunSummaryView(String id, String scenario, String status, @Nullable String haltReason,
                             String requirement, int planVersion, Instant createdAt, Instant updatedAt,
                             @Nullable Instant endedAt, @Nullable String autonomy, @Nullable String workspace,
                             @Nullable String startedBy, @Nullable String riskLevel, @Nullable String nodeId,
                             @Nullable Integer pendingActions, @Nullable ProgressView progress) {

    /**
     * Stage progress.
     *
     * @param succeeded succeeded stages
     * @param total total stages
     */
    public record ProgressView(int succeeded, int total) {
    }
}
