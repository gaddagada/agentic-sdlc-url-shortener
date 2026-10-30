package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.app.runs.RunConflictException;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;

/**
 * The hash chain invariant a store enforces at its boundary, with the same rules as the core's
 * {@code InMemoryAuditSink}: a batch of signed events must continue the run's stored chain (its first
 * {@code prevHash} is the hash of the last stored event, {@link AuditChain#GENESIS} for a run without signed events)
 * and must itself be an intact chain (consecutive sequence numbers, every {@code prevHash} the previous event's
 * {@code hash}, every {@code hash} the hash of the event's content). Unsigned (legacy) events are accepted as long as
 * the run's log is unsigned so far; a signed event after unsigned ones starts a new chain at the genesis hash.
 *
 * <p>A batch whose first event does not follow the stored head (its sequence number or {@code prevHash}) means that
 * another writer appended to the run after this node read it: that is a {@link RunConflictException} (the web layer
 * answers 409 {@code run-conflict}). A batch that is broken in itself is an {@link IllegalStateException}.</p>
 */
final class AuditChainContinuity {

    private AuditChainContinuity() {
    }

    /**
     * Checks that a batch of one run's events continues the run's stored chain.
     *
     * @param runId the run
     * @param last the run's last stored event, or null when it has none
     * @param batch the events to append, in sequence order
     * @throws IllegalStateException when the batch does not continue the stored chain or is not an intact chain
     */
    static void check(String runId, @Nullable AuditEvent last, List<AuditEvent> batch) {
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
                String message = "audit event seq " + event.seq() + " of run " + runId + " does not follow seq "
                        + previous.seq() + (first ? " (the last stored event)" : "");
                throw first && last != null ? new RunConflictException(message) : new IllegalStateException(message);
            }
            if (!expectedPrev.equals(event.prevHash())) {
                String message = (first ? "audit batch of run " + runId + " does not continue the stored hash chain"
                        : "audit batch of run " + runId + " is not an intact chain") + ": seq " + event.seq()
                        + " has prevHash " + event.prevHash() + ", expected " + expectedPrev;
                throw first ? new RunConflictException(message) : new IllegalStateException(message);
            }
            String recomputed = AuditChain.hashOf(event);
            if (!recomputed.equals(event.hash())) {
                throw new IllegalStateException("audit event seq " + event.seq() + " of run " + runId
                        + " has hash " + event.hash() + " but its content hashes to " + recomputed);
            }
            previous = event;
        }
    }
}
