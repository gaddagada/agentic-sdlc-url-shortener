package com.agenticsdlc.orchestrator.core.artifact;

import java.time.Instant;

/**
 * An immutable, versioned output of a stage. Artifacts are never modified: rework, feedback loops and
 * re-planning produce new versions, and the run keeps the full history.
 *
 * @param id artifact id (UUID)
 * @param runId owning run id
 * @param stageId producing stage id
 * @param kind artifact kind
 * @param version version per stage, starting at 1 and unique within the run
 * @param planVersion plan version of the run when produced
 * @param hash SHA-256 of the canonical JSON of the payload
 * @param payload the typed payload
 * @param provenance lineage and agent information
 * @param createdAt creation time
 */
public record Artifact(
        String id,
        String runId,
        String stageId,
        ArtifactKind kind,
        int version,
        int planVersion,
        String hash,
        ArtifactPayload payload,
        Provenance provenance,
        Instant createdAt) {

    /**
     * Returns the payload cast to the requested type.
     *
     * @param type payload type
     * @param <T> payload type
     * @return the payload
     * @throws ClassCastException when the payload has a different type
     */
    public <T extends ArtifactPayload> T payloadAs(Class<T> type) {
        return type.cast(payload);
    }

    /**
     * Returns the Markdown rendering of the payload.
     *
     * @return Markdown text
     */
    public String markdown() {
        return payload.markdown();
    }

    /**
     * Returns a short human-readable reference, for example {@code design@v2}.
     *
     * @return the reference
     */
    public String reference() {
        return stageId + "@v" + version;
    }
}
