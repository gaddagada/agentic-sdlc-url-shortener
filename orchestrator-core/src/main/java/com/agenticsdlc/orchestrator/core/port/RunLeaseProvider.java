package com.agenticsdlc.orchestrator.core.port;

import java.time.Duration;
import java.util.Optional;

/**
 * Grants time-bounded leases on runs so that several engine nodes can share one {@link RunStore} (SPI). A run is
 * leased by the node that is executing its stages; the engine refuses to mutate a run whose lease another node holds
 * (it throws {@code RunLeasedElsewhereException}). The engine acquires the lease when a command or internal event
 * schedules work on the run, renews it while it executes (at every mutation and periodically while an agent attempt
 * runs) and releases it when the run reaches a waiting or terminal state (WAITING_HUMAN, SAFE_STOPPED, COMPLETED,
 * ABORTED). The in-memory per-run lock stays the mechanism within one node.
 *
 * <p>Implementations must be thread-safe and linearizable per run (for example a row per run updated with
 * conditional statements: {@code INSERT} or {@code UPDATE ... WHERE expires_at <= now}, {@code UPDATE ... WHERE
 * token = ?}). A lease whose expiry has passed may be taken over by any owner; expiry is judged with the provider's
 * clock.</p>
 *
 * <p>Semantics expected by the engine:</p>
 * <ul>
 *   <li>{@link #tryAcquire} grants a new lease (new token) when the run has no lease or its lease expired; it fails
 *       while any valid lease exists, including one of the same owner (not re-entrant);</li>
 *   <li>{@link #renew} extends the lease identified by its token, also after its expiry as long as no other grant
 *       replaced it; it returns false once the token no longer holds the run;</li>
 *   <li>{@link #release} removes the lease if the token still holds it (otherwise it does nothing);</li>
 *   <li>{@link #holderOf} reports the owner of a valid (unexpired) lease.</li>
 * </ul>
 */
public interface RunLeaseProvider {

    /**
     * Tries to lease a run.
     *
     * @param runId run id
     * @param ownerId id of the acquiring node
     * @param ttl lease duration (positive)
     * @return the new lease, or empty when another valid lease exists
     */
    Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl);

    /**
     * Extends a lease to now plus {@code ttl}.
     *
     * @param lease the lease (identified by run id and token)
     * @param ttl new duration from now (positive)
     * @return true when the lease still held the run and was extended; false when it was released or replaced
     */
    boolean renew(RunLease lease, Duration ttl);

    /**
     * Releases a lease; does nothing when the token no longer holds the run.
     *
     * @param lease the lease
     */
    void release(RunLease lease);

    /**
     * Returns the owner of the run's valid lease.
     *
     * @param runId run id
     * @return the owner id, or empty when the run is not leased or its lease expired
     */
    Optional<String> holderOf(String runId);
}
