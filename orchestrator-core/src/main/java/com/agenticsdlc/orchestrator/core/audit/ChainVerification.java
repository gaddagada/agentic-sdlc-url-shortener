package com.agenticsdlc.orchestrator.core.audit;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Result of verifying the hash chain of a run's audit events ({@link AuditChain#verify(java.util.List)}).
 *
 * @param valid whether every examined event is signed, in sequence, linked to its predecessor and unmodified
 * @param length number of events verified as an intact chain: all of them when {@code valid}, otherwise the intact
 *               prefix before the first problem
 * @param brokenAtSeq sequence number of the first event with a problem (as found at the broken position), or null
 *                    when valid
 * @param expectedHash for a link or content problem, the hash the chain requires at that position (the previous
 *                     event's hash for a broken link, the recomputed hash for modified content); otherwise null
 * @param actualHash for a link or content problem, the hash found in the event ({@code prevHash} for a broken link,
 *                   {@code hash} for modified content); otherwise null
 * @param summary human-readable explanation
 */
public record ChainVerification(boolean valid, long length, @Nullable Long brokenAtSeq, @Nullable String expectedHash,
                                @Nullable String actualHash, String summary) {

    /**
     * Validates the result.
     */
    public ChainVerification {
        Objects.requireNonNull(summary, "summary");
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
    }

    /**
     * Returns a successful verification.
     *
     * @param length number of verified events
     * @param summary explanation
     * @return the result
     */
    public static ChainVerification ok(long length, String summary) {
        return new ChainVerification(true, length, null, null, null, summary);
    }

    /**
     * Returns a failed verification.
     *
     * @param length length of the intact prefix
     * @param brokenAtSeq sequence number of the first problematic event
     * @param expectedHash hash the chain requires at that position, or null
     * @param actualHash hash found, or null
     * @param summary explanation
     * @return the result
     */
    public static ChainVerification broken(long length, long brokenAtSeq, @Nullable String expectedHash,
                                           @Nullable String actualHash, String summary) {
        return new ChainVerification(false, length, brokenAtSeq, expectedHash, actualHash, summary);
    }
}
