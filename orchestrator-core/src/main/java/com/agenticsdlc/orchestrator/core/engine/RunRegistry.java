package com.agenticsdlc.orchestrator.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.port.RunLease;

/**
 * In-memory registry (cache) of runs: one {@link RunHolder} (run, lock, in-flight attempts, lease) per run. All run
 * mutations happen under the holder's lock. Runs that are not registered (never seen, or evicted) are loaded lazily
 * from the store when a mutation needs them (rehydration).
 *
 * <p>Bounded retention ({@link #evictIdle}): a run that is not executing on this node (not RUNNING, no in-flight
 * attempt, no lease held) is evicted once it has not changed for the retention period, and terminal runs beyond a
 * maximum count are evicted least recently ended first. Eviction never loses data: every mutation is committed to
 * the store before it becomes visible. A holder whose state can no longer be trusted (lease lost, failed commit) is
 * evicted at once ({@link #evictNow}).</p>
 */
final class RunRegistry {

    /**
     * A registered run with its lock, in-flight attempts and lease. Fields other than {@code lock} are guarded by
     * {@code lock}; the volatile ones may also be read without it.
     */
    static final class RunHolder {
        final ReentrantLock lock = new ReentrantLock();
        /**
         * Correlation tokens of attempts and retries, unique across all holders of this JVM: a holder that replaces an
         * abandoned one (lease lost, failed commit) never reuses a token of an attempt that is still running.
         */
        private static final AtomicLong TOKEN_SEQUENCE = new AtomicLong();

        /** The live aggregate; replaced under the lock when a newer state written by another node is loaded. */
        Run run;
        final Map<String, InFlight> inFlight = new HashMap<>();
        /** False for a new run until its first commit succeeded (runs loaded from the store are committed). */
        volatile boolean committed = true;
        /**
         * Set when the first commit of a new run failed: the run is being unregistered, so a mutation that obtained
         * the holder before (for example the completion of an attempt that finished meanwhile) must not commit it.
         */
        volatile boolean discarded;
        /**
         * Set when the holder was evicted (idle, or abandoned because its lease was lost or a commit failed): a
         * mutation that obtained it before must resolve the run again (from the store).
         */
        volatile boolean evicted;
        /** The lease this node holds on the run, or null. Written under the lock, read by attempt heartbeats. */
        volatile @Nullable RunLease lease;
        /**
         * Set while {@link #lease} is kept only because releasing it failed (the run is not executing here): the next
         * command of this node renews and uses it, and the release is retried at the end of every mutation, by the
         * engine's maintenance ({@code releasePendingLeases}) and on close. Such a run is never evicted.
         */
        volatile boolean releasePending;

        RunHolder(Run run) {
            this.run = run;
        }

        long nextToken() {
            return TOKEN_SEQUENCE.incrementAndGet();
        }
    }

    /**
     * An in-flight attempt.
     *
     * @param token correlation token
     * @param future the attempt future
     */
    record InFlight(long token, Future<?> future) {
    }

    private final ConcurrentHashMap<String, RunHolder> holders = new ConcurrentHashMap<>();
    private final Function<String, Optional<Run>> loader;

    RunRegistry(Function<String, Optional<Run>> loader) {
        this.loader = loader;
    }

    /**
     * Registers a new run unless a run with the same id is registered.
     *
     * @param run the run
     * @return the registered holder
     */
    RunHolder register(Run run) {
        return holders.computeIfAbsent(run.id(), id -> new RunHolder(run));
    }

    /**
     * Registers a run just loaded from the store unless a run with the same id is registered.
     *
     * @param run the loaded run
     * @return the registered holder (the existing one when the run was registered already)
     */
    RunHolder registerLoaded(Run run) {
        return holders.computeIfAbsent(run.id(), id -> new RunHolder(run));
    }

    /**
     * Evicts a holder right away, whatever its state, because its in-memory state can no longer be trusted (this node
     * lost the run's lease, or a commit failed): it is marked evicted and unregistered, so the next mutation or read
     * of the run loads it from the store. The caller holds the holder's lock.
     *
     * @param holder the holder
     */
    void evictNow(RunHolder holder) {
        holder.evicted = true;
        holders.remove(holder.run.id(), holder);
    }

    /**
     * Unregisters a run (used when the creation of a new run could not be committed, so that no run that was
     * never persisted stays visible).
     *
     * @param runId run id
     * @return the removed holder, or null when the run was not registered
     */
    @Nullable RunHolder remove(String runId) {
        return holders.remove(runId);
    }

    /**
     * Returns the holder of a run, loading it from the store and registering it if needed (rehydration).
     *
     * @param runId run id
     * @return the holder
     * @throws RunNotFoundException when unknown
     */
    RunHolder holder(String runId) {
        RunHolder holder = holders.get(runId);
        if (holder != null) {
            return holder;
        }
        Optional<Run> loaded = loader.apply(runId);
        if (loaded.isEmpty()) {
            throw new RunNotFoundException(runId);
        }
        return registerLoaded(loaded.get());
    }

    /**
     * Returns the holder if registered.
     *
     * @param runId run id
     * @return the holder or null
     */
    @Nullable RunHolder find(String runId) {
        return holders.get(runId);
    }

    /**
     * Returns all registered holders.
     *
     * @return holders
     */
    Collection<RunHolder> all() {
        return List.copyOf(holders.values());
    }

    /**
     * Returns the number of registered runs.
     *
     * @return count
     */
    int size() {
        return holders.size();
    }

    /**
     * Evicts idle runs: every idle run whose last change is at least {@code retention} old, then the least recently
     * ended terminal runs beyond {@code maxTerminalRuns}. A run is idle when it is committed, not RUNNING, has no
     * in-flight attempt, no lease held by this node and its last commit did not fail. Holders that are locked
     * right now are skipped (they are being used).
     *
     * @param now current time
     * @param retention retention of idle runs after their last change
     * @param maxTerminalRuns maximum number of cached terminal runs
     * @return ids of the evicted runs
     */
    List<String> evictIdle(Instant now, Duration retention, int maxTerminalRuns) {
        List<String> evicted = new ArrayList<>();
        List<TerminalCandidate> terminal = new ArrayList<>();
        for (RunHolder holder : holders.values()) {
            if (!holder.lock.tryLock()) {
                continue;
            }
            try {
                if (!idle(holder)) {
                    continue;
                }
                Run run = holder.run;
                if (!run.updatedAt().plus(retention).isAfter(now)) {
                    evict(holder, evicted);
                } else if (run.status().isTerminal()) {
                    Instant ended = run.endedAt() != null ? run.endedAt() : run.updatedAt();
                    terminal.add(new TerminalCandidate(holder, run.id(), ended));
                }
            } finally {
                holder.lock.unlock();
            }
        }
        int excess = terminal.size() - maxTerminalRuns;
        if (excess > 0) {
            terminal.sort(Comparator.comparing(TerminalCandidate::endedAt).thenComparing(TerminalCandidate::runId));
            for (TerminalCandidate candidate : terminal) {
                if (excess <= 0) {
                    break;
                }
                RunHolder holder = candidate.holder();
                if (!holder.lock.tryLock()) {
                    continue;
                }
                try {
                    if (idle(holder)) {
                        evict(holder, evicted);
                        excess--;
                    }
                } finally {
                    holder.lock.unlock();
                }
            }
        }
        return evicted;
    }

    private static boolean idle(RunHolder holder) {
        return !holder.evicted && !holder.discarded && holder.committed && holder.lease == null
                && !holder.releasePending
                && holder.inFlight.isEmpty() && holder.run.status() != RunStatus.RUNNING;
    }

    private void evict(RunHolder holder, List<String> evicted) {
        holder.evicted = true;
        String runId = holder.run.id();
        holders.remove(runId, holder);
        evicted.add(runId);
    }

    private record TerminalCandidate(RunHolder holder, String runId, Instant endedAt) {
    }
}
