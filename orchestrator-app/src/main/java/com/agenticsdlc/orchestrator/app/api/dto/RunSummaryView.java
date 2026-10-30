package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Compact view of a run for the runs list.
 *
 * @param id run id
 * @param scenario scenario key
 * @param status run status
 * @param haltReason halt reason, or null
 * @param requirement original requirement truncated to 120 characters
 * @param planVersion plan version
 * @param createdAt creation time
 * @param updatedAt last update
 * @param pendingActions number of pending human actions
 * @param progress succeeded and total stages
 */
public record RunSummaryView(String id, String scenario, String status, @Nullable String haltReason,
                             String requirement, int planVersion, Instant createdAt, Instant updatedAt,
                             int pendingActions, ProgressView progress) {

    /**
     * Stage progress.
     *
     * @param succeeded succeeded stages
     * @param total total stages
     */
    public record ProgressView(int succeeded, int total) {
    }
}
