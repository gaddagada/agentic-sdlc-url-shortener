package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;

/**
 * Summary of one artifact version.
 *
 * @param id artifact id
 * @param stageId producing stage
 * @param kind artifact kind
 * @param version version (per stage)
 * @param planVersion plan version it was produced under
 * @param hash SHA-256 of the canonical payload JSON
 * @param accepted whether this is the stage's accepted version
 * @param createdAt creation time
 * @param provenance provenance
 */
public record ArtifactSummaryView(String id, String stageId, String kind, int version, int planVersion, String hash,
                                  boolean accepted, Instant createdAt, ProvenanceView provenance) {
}
