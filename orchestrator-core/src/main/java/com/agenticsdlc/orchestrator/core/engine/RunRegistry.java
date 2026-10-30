package com.agenticsdlc.orchestrator.core.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * In-memory registry of live runs: one {@link RunHolder} (run, lock, in-flight attempts) per run. All run
 * mutations happen inside {@link #withLock(String, Function)}. Runs that are not registered yet are loaded
 * lazily from the store.
 */
final class RunRegistry {

    /**
     * A registered run with its lock and in-flight attempts. Fields other than {@code lock} are guarded by
     * {@code lock}.
     */
    static final class RunHolder {
        final ReentrantLock lock = new ReentrantLock();
        final Run run;
        final Map<String, InFlight> inFlight = new HashMap<>();
        private long tokenSequence;
        /** False for a new run until its first commit succeeded (runs loaded from the store are committed). */
        volatile boolean committed = true;
        /**
         * Set when the first commit of a new run failed: the run is being unregistered, so a mutation that obtained
         * the holder before (for example the completion of an attempt that finished meanwhile) must not commit it.
         */
        volatile boolean discarded;

        RunHolder(Run run) {
            this.run = run;
        }

        long nextToken() {
            return ++tokenSequence;
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
     * Registers a run unless a run with the same id is registered.
     *
     * @param run the run
     * @return the registered holder
     */
    RunHolder register(Run run) {
        return holders.computeIfAbsent(run.id(), id -> new RunHolder(run));
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
     * Returns the holder of a run, loading it from the store if needed.
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
        return register(loaded.get());
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
     * Runs {@code fn} while holding the run's lock.
     *
     * @param runId run id
     * @param fn function
     * @param <T> result type
     * @return the result
     */
    <T> T withLock(String runId, Function<RunHolder, T> fn) {
        RunHolder holder = holder(runId);
        holder.lock.lock();
        try {
            return fn.apply(holder);
        } finally {
            holder.lock.unlock();
        }
    }
}
