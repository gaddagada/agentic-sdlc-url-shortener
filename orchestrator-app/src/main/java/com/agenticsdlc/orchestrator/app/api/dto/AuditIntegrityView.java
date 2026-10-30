package com.agenticsdlc.orchestrator.app.api.dto;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.audit.ChainVerification;

/**
 * Result of verifying the hash chain of a run's stored audit trail: every event must be signed, in sequence, linked
 * to its predecessor ({@code prevHash} is the previous event's hash, 64 zeros for the first event) and unmodified
 * (its {@code hash} is the SHA-256 of its canonical content). A valid chain proves that the stored trail was not
 * edited, reordered or cut in the middle since it was written; it does not prove who wrote it (the chain is not
 * keyed), and removing events from the end is only detectable against a head hash known from elsewhere.
 *
 * @param valid whether the whole trail is an intact chain
 * @param length number of events verified as an intact chain (all events when valid, otherwise the intact prefix)
 * @param brokenAtSeq sequence number of the first event with a problem, or null when valid
 * @param expectedHash for a broken link or modified content, the hash the chain requires at that position, else null
 * @param actualHash for a broken link or modified content, the hash found in the event, else null
 * @param summary human-readable explanation (for example the head hash, or why the chain is broken or unsigned)
 */
public record AuditIntegrityView(boolean valid, long length, @Nullable Long brokenAtSeq, @Nullable String expectedHash,
                                 @Nullable String actualHash, String summary) {

    /**
     * Maps a core verification result.
     *
     * @param verification the result
     * @return the view
     */
    public static AuditIntegrityView from(ChainVerification verification) {
        return new AuditIntegrityView(verification.valid(), verification.length(), verification.brokenAtSeq(),
                verification.expectedHash(), verification.actualHash(), verification.summary());
    }
}
