package com.agenticsdlc.orchestrator.core.port;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.agenticsdlc.orchestrator.core.util.Clocks;
import com.agenticsdlc.orchestrator.core.util.Ids;

/**
 * Thread-safe in-memory {@link RunLeaseProvider}: the default of a single engine, and a shared instance lets several
 * engines in one JVM (tests) behave like nodes sharing a database. Expiry is judged with the given clock (the engine
 * clock by default, so tests can move time); an expired lease can be taken over by any owner.
 */
public final class InMemoryRunLeaseProvider implements RunLeaseProvider {

    private final ConcurrentHashMap<String, RunLease> leases = new ConcurrentHashMap<>();
    private final Clock clock;

    /**
     * Creates a provider using the system UTC clock.
     */
    public InMemoryRunLeaseProvider() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a provider using the given clock.
     *
     * @param clock clock used for expiry
     */
    public InMemoryRunLeaseProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerId, "ownerId");
        requirePositive(ttl);
        Instant now = Clocks.now(clock);
        AtomicReference<RunLease> granted = new AtomicReference<>();
        leases.compute(runId, (id, current) -> {
            if (current != null && !current.expiredAt(now)) {
                return current;
            }
            RunLease lease = new RunLease(id, ownerId, Ids.newId(), now.plus(ttl));
            granted.set(lease);
            return lease;
        });
        return Optional.ofNullable(granted.get());
    }

    /**
     * Grants a new lease to {@code ownerId} when the run has no valid lease or its valid lease belongs to
     * {@code ownerId} (the old token stops holding the run); fails while another owner holds a valid lease.
     */
    @Override
    public Optional<RunLease> reclaim(String runId, String ownerId, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerId, "ownerId");
        requirePositive(ttl);
        Instant now = Clocks.now(clock);
        AtomicReference<RunLease> granted = new AtomicReference<>();
        leases.compute(runId, (id, current) -> {
            if (current != null && !current.expiredAt(now) && !current.ownerId().equals(ownerId)) {
                return current;
            }
            RunLease lease = new RunLease(id, ownerId, Ids.newId(), now.plus(ttl));
            granted.set(lease);
            return lease;
        });
        return Optional.ofNullable(granted.get());
    }

    @Override
    public boolean renew(RunLease lease, Duration ttl) {
        Objects.requireNonNull(lease, "lease");
        requirePositive(ttl);
        Instant now = Clocks.now(clock);
        AtomicBoolean renewed = new AtomicBoolean();
        leases.computeIfPresent(lease.runId(), (id, current) -> {
            if (!current.token().equals(lease.token())) {
                return current;
            }
            renewed.set(true);
            return current.withExpiresAt(now.plus(ttl));
        });
        return renewed.get();
    }

    @Override
    public void release(RunLease lease) {
        Objects.requireNonNull(lease, "lease");
        leases.computeIfPresent(lease.runId(), (id, current) -> current.token().equals(lease.token()) ? null : current);
    }

    @Override
    public Optional<String> holderOf(String runId) {
        RunLease current = leases.get(runId);
        if (current == null || current.expiredAt(Clocks.now(clock))) {
            return Optional.empty();
        }
        return Optional.of(current.ownerId());
    }

    /**
     * Returns the run's current lease record, valid or expired (diagnostics and tests).
     *
     * @param runId run id
     * @return the lease, or empty when the run has none
     */
    public Optional<RunLease> current(String runId) {
        return Optional.ofNullable(leases.get(runId));
    }

    private static void requirePositive(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("lease ttl must be positive: " + ttl);
        }
    }

    @Override
    public String toString() {
        return "InMemoryRunLeaseProvider[leases=" + leases.size() + "]";
    }
}
