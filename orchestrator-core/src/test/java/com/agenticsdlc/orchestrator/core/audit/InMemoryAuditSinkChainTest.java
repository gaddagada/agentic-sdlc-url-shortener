package com.agenticsdlc.orchestrator.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InMemoryAuditSinkChainTest {

    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");

    private static AuditEvent event(String runId, long seq) {
        return new AuditEvent(seq, T0.plusSeconds(seq), runId, null, AuditEventType.STAGE_READY, Actor.system(),
                "event " + seq, Map.of("seq", seq));
    }

    /** Events {@code from..to} of a run, chained from {@code prevHash}. */
    private static List<AuditEvent> chained(String runId, long from, long to, String prevHash) {
        List<AuditEvent> events = new ArrayList<>();
        String prev = prevHash;
        for (long seq = from; seq <= to; seq++) {
            AuditEvent linked = AuditChain.link(event(runId, seq), prev);
            events.add(linked);
            prev = linked.hash();
        }
        return events;
    }

    @Test
    void batchesThatContinueTheChainAreAccepted() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        List<AuditEvent> first = chained("run-1", 1, 3, AuditChain.GENESIS);
        sink.append(first);
        sink.append(chained("run-1", 4, 6, first.getLast().hash()));
        sink.append(List.of());

        assertThat(sink.forRun("run-1", 0)).extracting(AuditEvent::seq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(AuditChain.verify(sink.forRun("run-1", 0)).valid()).isTrue();
        assertThat(sink.lastEvent("run-1")).map(AuditEvent::seq).contains(6L);
        assertThat(sink.lastEvent("unknown")).isEmpty();
    }

    @Test
    void aBatchThatDoesNotContinueTheStoredChainIsRejectedAndNothingIsStored() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.append(chained("run-1", 1, 2, AuditChain.GENESIS));

        assertThatThrownBy(() -> sink.append(chained("run-1", 3, 4, "c".repeat(64))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not continue the stored hash chain");
        assertThatThrownBy(() -> sink.append(chained("run-1", 1, 1, AuditChain.GENESIS)))
                .as("a second genesis (replayed or forked chain)").isInstanceOf(IllegalStateException.class);
        assertThat(sink.forRun("run-1", 0)).hasSize(2);
    }

    @Test
    void aBatchWhoseInternalChainIsBrokenIsRejectedAtomically() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        List<AuditEvent> stored = chained("run-1", 1, 2, AuditChain.GENESIS);
        sink.append(stored);
        List<AuditEvent> batch = new ArrayList<>(chained("run-1", 3, 5, stored.getLast().hash()));
        batch.set(2, AuditChain.link(event("run-1", 5), "d".repeat(64)));

        assertThatThrownBy(() -> sink.append(batch)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an intact chain");
        assertThat(sink.forRun("run-1", 0)).as("no event of the rejected batch was stored").hasSize(2);
    }

    @Test
    void tamperedHashesAndSequenceGapsAreRejected() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        List<AuditEvent> stored = chained("run-1", 1, 2, AuditChain.GENESIS);
        sink.append(stored);

        AuditEvent next = chained("run-1", 3, 3, stored.getLast().hash()).getFirst();
        AuditEvent forged = new AuditEvent(next.seq(), next.timestamp(), next.runId(), null, next.type(), next.actor(),
                "forged message", next.data(), next.prevHash(), next.hash());
        assertThatThrownBy(() -> sink.append(List.of(forged))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content hashes to");

        List<AuditEvent> gap = chained("run-1", 4, 4, stored.getLast().hash());
        assertThatThrownBy(() -> sink.append(gap)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not follow seq 2");

        assertThatThrownBy(() -> sink.append(List.of(next.withChain(next.prevHash(), null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only one of");
        assertThatThrownBy(() -> sink.append(List.of(event("run-1", 3))))
                .as("an unsigned event after signed ones").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("would break");
        sink.append(List.of(next));
        assertThat(sink.forRun("run-1", 0)).hasSize(3);
    }

    @Test
    void unsignedLegacyEventsAreStillAcceptedAndASignedChainMayFollowThem() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.append(List.of(event("legacy", 1), event("legacy", 2)));
        sink.append(chained("legacy", 3, 4, AuditChain.GENESIS));

        assertThat(sink.forRun("legacy", 0)).hasSize(4);
        assertThat(AuditChain.verify(sink.forRun("legacy", 0)).summary()).contains("unsigned");
        assertThat(AuditChain.verify(sink.forRun("legacy", 2), 2, AuditChain.GENESIS).valid()).isTrue();
    }

    @Test
    void eventsOfDifferentRunsAreCheckedPerRun() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        List<AuditEvent> mixed = new ArrayList<>(chained("run-a", 1, 2, AuditChain.GENESIS));
        mixed.addAll(chained("run-b", 1, 2, AuditChain.GENESIS));
        sink.append(mixed);
        assertThat(sink.forRun("run-a", 0)).hasSize(2);
        assertThat(sink.forRun("run-b", 0)).hasSize(2);
    }

    @Test
    void theDefaultLastEventReadsTheWholeLog() {
        List<AuditEvent> stored = new ArrayList<>(chained("run-1", 1, 3, AuditChain.GENESIS));
        AuditSink sink = new AuditSink() {
            @Override
            public void append(List<AuditEvent> events) {
                stored.addAll(events);
            }

            @Override
            public List<AuditEvent> forRun(String runId, long afterSeq) {
                return stored.stream().filter(e -> e.runId().equals(runId) && e.seq() > afterSeq).toList();
            }
        };
        assertThat(sink.lastEvent("run-1")).map(AuditEvent::seq).contains(3L);
        assertThat(sink.lastEvent("run-2")).isEmpty();
    }
}
