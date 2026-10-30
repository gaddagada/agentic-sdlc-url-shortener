package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One artifact version with its payload and Markdown rendering.
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
 * @param payload the payload as a JSON object
 * @param markdown Markdown rendering of the payload
 */
public record ArtifactView(String id, String stageId, String kind, int version, int planVersion, String hash,
                           boolean accepted, Instant createdAt, ProvenanceView provenance, Map<String, Object> payload,
                           String markdown) {
}
