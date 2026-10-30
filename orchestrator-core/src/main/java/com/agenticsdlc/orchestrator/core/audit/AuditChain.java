package com.agenticsdlc.orchestrator.core.audit;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.util.Hashes;

/**
 * Hash chaining of a run's audit events (tamper evidence for the append-only audit log).
 *
 * <p>Every event the engine writes carries {@code prevHash} (the {@code hash} of the run's previous event, or
 * {@link #GENESIS} for the run's first event) and {@code hash}, the lowercase hex SHA-256 of the UTF-8 bytes of the
 * event's canonical form ({@link #canonicalForm(AuditEvent)}): a JSON object, written with
 * {@link JsonCodec#strictCanonicalJson(Object)}, with exactly these members:</p>
 * <ul>
 *   <li>{@code seq} (number), {@code timestamp} (ISO-8601 UTC, {@link DateTimeFormatter#ISO_INSTANT}),
 *       {@code runId}, {@code stageId} (empty string for run-level events), {@code type} (enum name),</li>
 *   <li>{@code actorType} (enum name) and {@code actorId}, {@code message},</li>
 *   <li>{@code data} (the canonical JSON object of the event data: keys sorted at every level, numbers
 *       normalized),</li>
 *   <li>{@code prevHash} ({@link #GENESIS} when absent).</li>
 * </ul>
 *
 * <p>Changing any field of an event changes its hash; removing, inserting or reordering events breaks the
 * {@code prevHash} links or the sequence. The chain is not keyed: it proves integrity relative to a trusted head
 * hash (for example one exported or anchored elsewhere), not authorship.</p>
 */
public final class AuditChain {

    /** The {@code prevHash} of the first event of a run: 64 zero hex characters. */
    public static final String GENESIS = "0".repeat(64);

    private AuditChain() {
    }

    /**
     * Returns the canonical form of an event that its hash is computed over. The event's own {@code hash} is not
     * part of it.
     *
     * @param event the event
     * @return canonical JSON text
     */
    public static String canonicalForm(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        Map<String, @Nullable Object> form = new LinkedHashMap<>();
        form.put("seq", event.seq());
        form.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(event.timestamp()));
        form.put("runId", event.runId());
        form.put("stageId", event.stageId() == null ? "" : event.stageId());
        form.put("type", event.type().name());
        form.put("actorType", event.actor().type().name());
        form.put("actorId", event.actor().id());
        form.put("message", event.message());
        form.put("data", event.data());
        form.put("prevHash", event.prevHash() == null ? GENESIS : event.prevHash());
        return JsonCodec.standard().strictCanonicalJson(form);
    }

    /**
     * Computes the hash of an event: SHA-256 (lowercase hex) of the UTF-8 bytes of {@link #canonicalForm}. The
     * event's {@code hash} field is ignored, so the event may be passed with or without it.
     *
     * @param event the event (its {@code prevHash} is part of the hash; null counts as {@link #GENESIS})
     * @return 64 lowercase hex characters
     */
    public static String hashOf(AuditEvent event) {
        return Hashes.sha256Hex(canonicalForm(event));
    }

    /**
     * Links an event to its predecessor: returns a copy with {@code prevHash} set and {@code hash} computed.
     *
     * @param event the event (its chain fields are replaced)
     * @param prevHash hash of the previous event of the run, or null for the first event ({@link #GENESIS})
     * @return the signed copy
     */
    public static AuditEvent link(AuditEvent event, @Nullable String prevHash) {
        String previous = prevHash == null ? GENESIS : prevHash;
        AuditEvent unsigned = event.withChain(previous, null);
        return unsigned.withChain(previous, hashOf(unsigned));
    }

    /**
     * Verifies the complete chain of one run: the events must be all events of the run in sequence order, starting
     * at sequence 1 with {@code prevHash} {@link #GENESIS}.
     *
     * @param eventsOfOneRunInSeqOrder the run's events in sequence order
     * @return the verification result; an empty list is a valid chain of length 0
     * @see #verify(List, long, String)
     */
    public static ChainVerification verify(List<AuditEvent> eventsOfOneRunInSeqOrder) {
        return verify(eventsOfOneRunInSeqOrder, 0L, GENESIS);
    }

    /**
     * Verifies a segment of one run's chain that continues after the event with sequence number {@code afterSeq}
     * whose hash is {@code anchorHash} (use {@code 0} and {@link #GENESIS} for the complete chain). Detected
     * problems, reported at the first event where they occur:
     * <ul>
     *   <li>an unsigned event (no {@code prevHash} or {@code hash}, written before hash chaining existed): the
     *       chain is reported as unsigned;</li>
     *   <li>an event of another run;</li>
     *   <li>a sequence gap, duplicate or reordering ({@code seq} is not the previous one plus 1);</li>
     *   <li>a broken link ({@code prevHash} is not the previous event's {@code hash}): an event was removed,
     *       inserted or replaced;</li>
     *   <li>modified content ({@code hash} differs from the recomputed hash).</li>
     * </ul>
     *
     * @param events the events of one run in sequence order
     * @param afterSeq sequence number of the event the segment continues ({@code 0} for the complete chain)
     * @param anchorHash hash of that event ({@link #GENESIS} for the complete chain)
     * @return the verification result
     */
    public static ChainVerification verify(List<AuditEvent> events, long afterSeq, String anchorHash) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(anchorHash, "anchorHash");
        if (events.isEmpty()) {
            return ChainVerification.ok(0, "empty chain: no events to verify");
        }
        String runId = events.getFirst().runId();
        long expectedSeq = afterSeq + 1;
        String expectedPrev = anchorHash;
        long verified = 0;
        for (AuditEvent event : events) {
            if (!event.signed()) {
                return ChainVerification.broken(verified, event.seq(), null, null, "chain is unsigned: event seq "
                        + event.seq() + " has no " + (event.hash() == null ? "hash" : "prevHash")
                        + " (written before hash chaining was enabled)");
            }
            if (!runId.equals(event.runId())) {
                return ChainVerification.broken(verified, event.seq(), null, null, "event seq " + event.seq()
                        + " belongs to run " + event.runId() + ", not " + runId);
            }
            if (event.seq() != expectedSeq) {
                return ChainVerification.broken(verified, event.seq(), null, null, "sequence broken: expected seq "
                        + expectedSeq + " but found " + event.seq() + " (event removed, duplicated or reordered)");
            }
            if (!expectedPrev.equals(event.prevHash())) {
                return ChainVerification.broken(verified, event.seq(), expectedPrev, event.prevHash(),
                        "link broken at seq " + event.seq() + ": prevHash does not match the hash of "
                                + (expectedSeq == 1 ? "the genesis" : "seq " + (expectedSeq - 1))
                                + " (event removed, inserted or replaced)");
            }
            String recomputed = hashOf(event);
            if (!recomputed.equals(event.hash())) {
                return ChainVerification.broken(verified, event.seq(), recomputed, event.hash(),
                        "content hash mismatch at seq " + event.seq() + ": the event was modified after it was "
                                + "written");
            }
            expectedPrev = recomputed;
            expectedSeq++;
            verified++;
        }
        return ChainVerification.ok(verified, verified + " events verified (seq " + (afterSeq + 1) + ".."
                + (expectedSeq - 1) + "); head hash " + expectedPrev);
    }
}
