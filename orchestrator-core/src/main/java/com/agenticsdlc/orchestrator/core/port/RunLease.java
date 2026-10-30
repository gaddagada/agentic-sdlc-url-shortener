package com.agenticsdlc.orchestrator.core.port;

import java.time.Instant;
import java.util.Objects;

/**
 * A time-bounded lease on a run, granted by a {@link RunLeaseProvider}: while it is valid, only its owner (an engine
 * node) may mutate the run.
 *
 * @param runId the leased run
 * @param ownerId id of the node holding the lease
 * @param token opaque token identifying this grant (a renewal or release must present it; a new grant, for example
 *              after expiry and takeover, gets a new token)
 * @param expiresAt when the lease expires unless renewed (as granted; a renewal extends the provider's record, not
 *                  this value)
 */
public record RunLease(String runId, String ownerId, String token, Instant expiresAt) {

    /**
     * Validates the lease.
     */
    public RunLease {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Returns whether the lease has expired at {@code now} (expiry is exclusive: at {@code expiresAt} it is expired).
     *
     * @param now the current time
     * @return true when {@code now} is at or after {@code expiresAt}
     */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Returns a copy with a different expiry.
     *
     * @param newExpiresAt the new expiry
     * @return the copy
     */
    public RunLease withExpiresAt(Instant newExpiresAt) {
        return new RunLease(runId, ownerId, token, newExpiresAt);
    }
}
