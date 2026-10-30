package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.model.PolicyFindingRecord;

/**
 * A policy finding recorded on a run.
 *
 * @param ruleId rule id, for example {@code GOV-001}
 * @param severity severity
 * @param verdict ALLOW, REQUIRE_APPROVAL or DENY
 * @param message message
 * @param stageId evaluated stage, or null at run start
 * @param phase RUN_START, ENTRY or EXIT
 * @param planVersion plan version
 * @param timestamp evaluation time
 */
public record PolicyFindingView(String ruleId, String severity, String verdict, String message,
                                @Nullable String stageId, String phase, int planVersion, Instant timestamp) {

    /**
     * Maps a core finding.
     *
     * @param finding the finding
     * @return the view
     */
    public static PolicyFindingView from(PolicyFindingRecord finding) {
        return new PolicyFindingView(finding.ruleId(), finding.severity().name(), finding.verdict(),
                finding.message(), finding.stageId(), finding.phase(), finding.planVersion(), finding.timestamp());
    }
}
