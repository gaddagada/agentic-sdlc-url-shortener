package com.agenticsdlc.orchestrator.core.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Thread-safe in-memory {@link AuditSink} (tests and default wiring).
 *
 * <p>It enforces the engine's hash chain invariant at the boundary: a batch of signed events is rejected with an
 * {@link IllegalStateException} (and nothing of that run's batch is stored) when its first {@code prevHash} does not
 * continue the stored chain of the run, or when the batch is not itself an intact chain (sequence numbers not
 * consecutive, a {@code prevHash} that is not the previous event's {@code hash}, or a {@code hash} that does not match
 * the event's content). Unsigned (legacy) events are accepted as long as the run's stored log is unsigned too; a
 * signed event after unsigned ones starts a new chain at {@link AuditChain#GENESIS}.</p>
 */
public final class InMemoryAuditSink implements AuditSink {

    private final Map<String, List<AuditEvent>> eventsByRun = new ConcurrentHashMap<>();

    /**
     * Creates an empty sink.
     */
    public InMemoryAuditSink() {
        // empty
    }

    /**
     * Appends events after checking that they continue each run's hash chain. Events of different runs are checked
     * and stored per run (a batch of the engine always belongs to one run).
     *
     * @param events events to append, in sequence order per run
     * @throws IllegalStateException when a run's events do not continue its stored chain or are not an intact chain
     */
    @Override
    public void append(List<AuditEvent> events) {
        Map<String, List<AuditEvent>> byRun = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            byRun.computeIfAbsent(event.runId(), k -> new ArrayList<>()).add(event);
        }
        byRun.forEach((runId, batch) -> {
            List<AuditEvent> list = eventsByRun.computeIfAbsent(runId, k -> new ArrayList<>());
            synchronized (list) {
                checkContinuesChain(runId, list.isEmpty() ? null : list.getLast(), batch);
                list.addAll(batch);
            }
        });
    }

    private static void checkContinuesChain(String runId, @Nullable AuditEvent last, List<AuditEvent> batch) {
        AuditEvent previous = last;
        for (AuditEvent event : batch) {
            boolean first = previous == last;
            if (!event.signed()) {
                if (event.prevHash() != null || event.hash() != null) {
                    throw new IllegalStateException("audit event seq " + event.seq() + " of run " + runId
                            + " has only one of prevHash and hash");
                }
                if (previous != null && previous.signed()) {
                    throw new IllegalStateException("unsigned audit event seq " + event.seq() + " of run " + runId
                            + " would break the run's hash chain");
                }
                previous = event;
                continue;
            }
            String expectedPrev = previous == null || !previous.signed() ? AuditChain.GENESIS : previous.hash();
            if (previous != null && previous.signed() && event.seq() != previous.seq() + 1) {
                throw new IllegalStateException("audit event seq " + event.seq() + " of run " + runId + " does not "
                        + "follow seq " + previous.seq() + (first ? " (the last stored event)" : ""));
            }
            if (!expectedPrev.equals(event.prevHash())) {
                throw new IllegalStateException((first ? "audit batch of run " + runId + " does not continue the "
                        + "stored hash chain" : "audit batch of run " + runId + " is not an intact chain")
                        + ": seq " + event.seq() + " has prevHash " + event.prevHash() + ", expected " + expectedPrev);
            }
            String recomputed = AuditChain.hashOf(event);
            if (!recomputed.equals(event.hash())) {
                throw new IllegalStateException("audit event seq " + event.seq() + " of run " + runId
                        + " has hash " + event.hash() + " but its content hashes to " + recomputed);
            }
            previous = event;
        }
    }

    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq) {
        List<AuditEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return list.stream().filter(e -> e.seq() > afterSeq).toList();
        }
    }

    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<AuditEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return list.stream().filter(e -> e.seq() > afterSeq).limit(limit).toList();
        }
    }

    @Override
    public Optional<AuditEvent> lastEvent(String runId) {
        List<AuditEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return Optional.empty();
        }
        synchronized (list) {
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getLast());
        }
    }

    /**
     * Returns all events of a run whose type is {@code type}.
     *
     * @param runId run id
     * @param type event type
     * @return matching events
     */
    public List<AuditEvent> ofType(String runId, AuditEventType type) {
        return forRun(runId, 0).stream().filter(e -> e.type() == type).toList();
    }
}
