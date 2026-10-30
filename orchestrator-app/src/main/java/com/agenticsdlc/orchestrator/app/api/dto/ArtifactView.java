package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One artifact version with its payload and Markdown rendering. For a release-readiness artifact it also reports
 * the signature of its change record and whether that signature verifies with the control plane's configured key.
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
 * @param signature the change record signature of a release-readiness artifact, or null (other kinds, or an
 *                  unsigned record: not finalized yet, or no signing key was configured when the run completed)
 * @param signatureVerified for a release-readiness artifact with a signature: whether it verifies with the
 *                          configured signer (same algorithm, key id and key, over the record as stored now); null
 *                          when there is no signature
 * @param dependencies for a security review: its dependency inventory (declared dependencies and build plugins of
 *                     the workspace's Maven files, empty for runs without a workspace) with the hygiene flags of every
 *                     declaration; null for other kinds
 */
public record ArtifactView(String id, String stageId, String kind, int version, int planVersion, String hash,
                           boolean accepted, Instant createdAt, ProvenanceView provenance, Map<String, Object> payload,
                           String markdown, @Nullable SignatureView signature, @Nullable Boolean signatureVerified,
                           @Nullable List<DependencyView> dependencies) {

    /**
     * The signature over a finalized release change record (HMAC-SHA256 of its canonical JSON without the
     * signature member).
     *
     * @param algorithm signature algorithm, for example {@code HmacSHA256}
     * @param keyId identifier of the signing key (never the key)
     * @param value the signature (lowercase hex)
     * @param signedAt ISO-8601 UTC time of signing (informational, not covered by the signature)
     */
    public record SignatureView(String algorithm, String keyId, String value, String signedAt) {
    }
}

