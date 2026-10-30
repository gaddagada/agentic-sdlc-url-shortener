package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One stage of a run.
 *
 * @param id stage id
 * @param name display name
 * @param role agent role
 * @param dependsOn predecessor stage ids
 * @param status stage status
 * @param attempts attempts of the current activation
 * @param activeAgent agent id of the latest attempt
 * @param startedAt start of the latest attempt
 * @param endedAt end of the latest attempt
 * @param durationMs duration of the latest attempt
 * @param lastError last error
 * @param artifactVersion the version to look at: the version under review while an exit approval is pending,
 *                        else the accepted version, or null
 * @param acceptedVersion the accepted artifact version, or null (while an exit approval is pending this is the
 *                        previously accepted version)
 * @param artifactKind artifact kind the stage produces
 * @param approval pending approval, else the latest approval of the stage, or null
 * @param feedback reviewer and verification feedback
 * @param nextAttemptAt next retry time
 */
public record StageView(
        String id,
        String name,
        String role,
        List<String> dependsOn,
        String status,
        int attempts,
        @Nullable String activeAgent,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable Long durationMs,
        @Nullable String lastError,
        @Nullable Integer artifactVersion,
        @Nullable Integer acceptedVersion,
        String artifactKind,
        @Nullable ApprovalView approval,
        List<String> feedback,
        @Nullable Instant nextAttemptAt) {
}
