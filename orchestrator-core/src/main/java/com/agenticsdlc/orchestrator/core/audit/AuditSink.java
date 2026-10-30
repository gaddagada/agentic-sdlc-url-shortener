package com.agenticsdlc.orchestrator.core.audit;

import java.util.List;
import java.util.Optional;

/**
 * Append-only audit log port. The engine appends the events of each command in one batch while holding
 * the run lock, after persisting the run (durability before visibility).
 *
 * <p>Events written by the engine are hash-chained per run ({@link AuditChain}): the first event of a batch
 * continues the hash of the run's last stored event. Implementations must store every field verbatim (including
 * {@code prevHash} and {@code hash}, the message and the data, with the timestamp at millisecond precision) so that
 * {@link AuditChain#verify(List)} succeeds on what {@link #forRun(String, long)} returns, and should reject a batch
 * that does not continue the stored chain (see {@link InMemoryAuditSink#append(List)}).</p>
 */
public interface AuditSink {

    /**
     * Appends events (all of the same run, in sequence order).
     *
     * @param events events to append
     */
    void append(List<AuditEvent> events);

    /**
     * Returns the last stored event of a run (the highest sequence number): the head of its hash chain. The engine
     * uses it to resynchronize its chain head with the log when it loads a run from the store or after a failed
     * commit. The default implementation reads the run's whole log; stores should override it with a bounded query
     * (for example {@code ORDER BY seq DESC LIMIT 1}).
     *
     * @param runId run id
     * @return the last event, or empty when the run has no events
     */
    default Optional<AuditEvent> lastEvent(String runId) {
        List<AuditEvent> all = forRun(runId, 0);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    /**
     * Returns the events of a run with {@code seq > afterSeq}, in sequence order.
     *
     * @param runId run id
     * @param afterSeq exclusive lower bound (0 for all)
     * @return events
     */
    List<AuditEvent> forRun(String runId, long afterSeq);

    /**
     * Returns at most {@code limit} events of a run with {@code seq > afterSeq}, in sequence order (a page:
     * continue with the {@code seq} of the last returned event). The default implementation truncates
     * {@link #forRun(String, long)}; stores should override it with a bounded query.
     *
     * @param runId run id
     * @param afterSeq exclusive lower bound (0 for all)
     * @param limit maximum number of events (positive)
     * @return at most {@code limit} events
     * @throws IllegalArgumentException when {@code limit} is not positive
     */
    default List<AuditEvent> forRun(String runId, long afterSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<AuditEvent> all = forRun(runId, afterSeq);
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }
}
