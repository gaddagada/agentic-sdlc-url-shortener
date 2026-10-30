package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Severity;

/**
 * A policy finding recorded on the run (the model keeps its own copy so it does not depend on the policy
 * package; {@code verdict} and {@code phase} hold the enum names of {@code policy.Verdict} and
 * {@code policy.GatePhase}).
 *
 * @param ruleId rule id, for example {@code GOV-001}
 * @param severity severity
 * @param verdict verdict name ({@code ALLOW}, {@code REQUIRE_APPROVAL}, {@code DENY})
 * @param message message
 * @param stageId stage, or null at run start
 * @param phase phase name ({@code RUN_START}, {@code ENTRY}, {@code EXIT})
 * @param planVersion plan version
 * @param timestamp evaluation time
 */
public record PolicyFindingRecord(String ruleId, Severity severity, String verdict, String message,
                                  @Nullable String stageId, String phase, int planVersion, Instant timestamp) {
}
