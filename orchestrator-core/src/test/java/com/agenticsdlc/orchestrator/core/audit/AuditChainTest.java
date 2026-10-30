package com.agenticsdlc.orchestrator.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.json.JsonCodec;

import tools.jackson.core.type.TypeReference;

class AuditChainTest {

    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00.123Z");
    private static final String RUN = "run-1";

    private static AuditEvent unsigned(long seq, String message, Map<String, Object> data) {
        return new AuditEvent(seq, T0.plusMillis(seq), RUN, seq % 2 == 0 ? "design" : null,
                AuditEventType.STAGE_STARTED, seq % 3 == 0 ? Actor.human("alice") : Actor.system(), message, data);
    }

    /** A valid chain of {@code count} events with nested data. */
    private static List<AuditEvent> chain(int count) {
        List<AuditEvent> events = new ArrayList<>();
        String prev = null;
        for (long seq = 1; seq <= count; seq++) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attempt", seq);
            data.put("ratio", 0.5);
            data.put("tags", List.of("a", "b"));
            data.put("nested", Map.of("z", 1, "a", Map.of("k", true)));
            AuditEvent event = AuditChain.link(unsigned(seq, "event " + seq, data), prev);
            events.add(event);
            prev = event.hash();
        }
        return events;
    }

    @Test
    void genesisIsSixtyFourZerosAndTheFirstEventLinksToIt() {
        assertThat(AuditChain.GENESIS).hasSize(64).matches("0+");
        AuditEvent first = AuditChain.link(unsigned(1, "created", Map.of()), null);
        assertThat(first.prevHash()).isEqualTo(AuditChain.GENESIS);
        assertThat(first.hash()).hasSize(64).matches("[0-9a-f]+").isEqualTo(AuditChain.hashOf(first));
        assertThat(first.signed()).isTrue();
        assertThat(unsigned(1, "legacy", Map.of()).signed()).isFalse();
        assertThat(unsigned(1, "legacy", Map.of()).prevHash()).isNull();
        assertThat(AuditChain.hashOf(unsigned(1, "x", Map.of())))
                .as("a missing prevHash counts as genesis")
                .isEqualTo(AuditChain.hashOf(unsigned(1, "x", Map.of()).withChain(AuditChain.GENESIS, null)));
    }

    @Test
    void theHashIsDeterministicAndIndependentOfDataKeyOrderAndNumberTypes() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", Map.of("y", List.of(1, 2.0), "x", "text"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", new LinkedHashMap<>(Map.of("x", "text", "y", List.of(1L, 2))));
        second.put("b", 2L);

        AuditEvent a = AuditChain.link(unsigned(1, "m", first), null);
        AuditEvent b = AuditChain.link(unsigned(1, "m", second), null);
        assertThat(a.hash()).isEqualTo(b.hash()).isEqualTo(AuditChain.hashOf(a));
        assertThat(AuditChain.canonicalForm(a)).isEqualTo(AuditChain.canonicalForm(b))
                .startsWith("{\"actorId\":\"engine\",\"actorType\":\"SYSTEM\",\"data\":{\"a\":{\"x\":\"text\",\"y\":[1,2]},"
                        + "\"b\":2},\"message\":\"m\",\"prevHash\":\"" + AuditChain.GENESIS + "\",\"runId\":\"run-1\",")
                .contains("\"seq\":1,\"stageId\":\"\",\"timestamp\":\"2026-10-30T10:00:00.124Z\","
                        + "\"type\":\"STAGE_STARTED\"}");
    }

    @Test
    void everyFieldIsCoveredByTheHash() {
        AuditEvent base = AuditChain.link(unsigned(2, "message", Map.of("k", "v")), "a".repeat(64));
        String hash = base.hash();
        List<AuditEvent> variants = List.of(
                new AuditEvent(3, base.timestamp(), RUN, base.stageId(), base.type(), base.actor(), base.message(),
                        base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp().plusMillis(1), RUN, base.stageId(), base.type(), base.actor(),
                        base.message(), base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), "run-2", base.stageId(), base.type(), base.actor(), base.message(),
                        base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), RUN, "implement", base.type(), base.actor(), base.message(),
                        base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), RUN, base.stageId(), AuditEventType.STAGE_FAILED, base.actor(),
                        base.message(), base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), RUN, base.stageId(), base.type(), Actor.human("engine"),
                        base.message(), base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), RUN, base.stageId(), base.type(), Actor.system(), "message!",
                        base.data(), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), RUN, base.stageId(), base.type(), Actor.system(), base.message(),
                        Map.of("k", "w"), base.prevHash(), null),
                new AuditEvent(2, base.timestamp(), RUN, base.stageId(), base.type(), Actor.system(), base.message(),
                        base.data(), "b".repeat(64), null));
        assertThat(variants).extracting(AuditChain::hashOf).doesNotContain(hash).doesNotHaveDuplicates();
    }

    @Test
    void anIntactChainVerifies() {
        List<AuditEvent> events = chain(6);
        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isTrue();
        assertThat(result.length()).isEqualTo(6);
        assertThat(result.brokenAtSeq()).isNull();
        assertThat(result.summary()).contains("6 events verified").contains(events.getLast().hash());

        ChainVerification empty = AuditChain.verify(List.of());
        assertThat(empty.valid()).isTrue();
        assertThat(empty.length()).isZero();

        ChainVerification segment = AuditChain.verify(events.subList(3, 6), 3, events.get(2).hash());
        assertThat(segment.valid()).as("a segment verifies against its anchor").isTrue();
        assertThat(segment.length()).isEqualTo(3);
        assertThat(AuditChain.verify(events.subList(3, 6)).valid()).as("but is not a complete chain").isFalse();
    }

    @Test
    void modifiedContentIsDetected() {
        List<AuditEvent> events = new ArrayList<>(chain(5));
        AuditEvent original = events.get(2);
        AuditEvent tampered = new AuditEvent(original.seq(), original.timestamp(), RUN, original.stageId(),
                original.type(), original.actor(), "approved by mallory", original.data(), original.prevHash(),
                original.hash());
        events.set(2, tampered);

        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(3L);
        assertThat(result.length()).isEqualTo(2);
        assertThat(result.actualHash()).isEqualTo(original.hash());
        assertThat(result.expectedHash()).isEqualTo(AuditChain.hashOf(tampered)).isNotEqualTo(original.hash());
        assertThat(result.summary()).contains("content hash mismatch at seq 3");
    }

    @Test
    void modifiedDataIsDetected() {
        List<AuditEvent> events = new ArrayList<>(chain(3));
        AuditEvent original = events.get(1);
        Map<String, Object> data = new LinkedHashMap<>(original.data());
        data.put("attempt", 99);
        events.set(1, new AuditEvent(original.seq(), original.timestamp(), RUN, original.stageId(), original.type(),
                original.actor(), original.message(), data, original.prevHash(), original.hash()));

        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(2L);
        assertThat(result.summary()).contains("content hash mismatch");
    }

    @Test
    void aRemovedEventIsDetected() {
        List<AuditEvent> events = new ArrayList<>(chain(5));
        events.remove(2);

        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(4L);
        assertThat(result.summary()).contains("expected seq 3 but found 4");
    }

    @Test
    void reorderedEventsAreDetected() {
        List<AuditEvent> events = new ArrayList<>(chain(5));
        AuditEvent third = events.get(2);
        events.set(2, events.get(3));
        events.set(3, third);

        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(4L);
        assertThat(result.summary()).contains("sequence broken");
    }

    @Test
    void aRemovedAndRenumberedEventBreaksTheLink() {
        List<AuditEvent> events = new ArrayList<>(chain(5));
        events.remove(2);
        // the forger renumbers the next event and recomputes its own hash, but cannot fix the link without rewriting
        // every later event
        AuditEvent next = events.get(2);
        AuditEvent renumbered = new AuditEvent(3, next.timestamp(), RUN, next.stageId(), next.type(), next.actor(),
                next.message(), next.data(), next.prevHash(), null);
        events.set(2, renumbered.withChain(next.prevHash(), AuditChain.hashOf(renumbered)));

        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(3L);
        assertThat(result.expectedHash()).isEqualTo(events.get(1).hash());
        assertThat(result.actualHash()).isEqualTo(next.prevHash());
        assertThat(result.summary()).contains("link broken at seq 3");
    }

    @Test
    void aReplacedFirstEventMustStillStartAtGenesis() {
        List<AuditEvent> events = new ArrayList<>(chain(2));
        AuditEvent forged = AuditChain.link(unsigned(1, "forged", Map.of()), "f".repeat(64));
        events.set(0, forged);

        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(1L);
        assertThat(result.expectedHash()).isEqualTo(AuditChain.GENESIS);
        assertThat(result.summary()).contains("genesis");
    }

    @Test
    void legacyUnsignedEventsAreReportedAsAnUnsignedChain() {
        List<AuditEvent> legacy = List.of(unsigned(1, "a", Map.of()), unsigned(2, "b", Map.of()));
        ChainVerification result = AuditChain.verify(legacy);
        assertThat(result.valid()).isFalse();
        assertThat(result.length()).isZero();
        assertThat(result.brokenAtSeq()).isEqualTo(1L);
        assertThat(result.summary()).contains("chain is unsigned");

        List<AuditEvent> mixed = new ArrayList<>(chain(2));
        mixed.add(unsigned(3, "legacy tail", Map.of()));
        assertThat(AuditChain.verify(mixed).summary()).contains("unsigned").contains("seq 3");
        ChainVerification stripped = AuditChain.verify(mixed);
        assertThat(stripped.length()).isEqualTo(2);
        assertThat(stripped.brokenAtSeq()).isEqualTo(3L);
        assertThat(stripped.summary()).as("not excused as legacy after signed events")
                .startsWith("chain broken at seq 3: unsigned event (no hash) after 2 signed events")
                .doesNotContain("written before hash chaining was enabled");
        assertThat(AuditChain.verify(List.of(chain(1).getFirst())).valid()).as("a single event").isTrue();
    }

    @Test
    void eventsOfAnotherRunAreDetected() {
        List<AuditEvent> events = new ArrayList<>(chain(2));
        AuditEvent other = AuditChain.link(new AuditEvent(3, T0, "run-2", null, AuditEventType.RUN_COMPLETED,
                Actor.system(), "other", Map.of()), events.getLast().hash());
        events.add(other);
        ChainVerification result = AuditChain.verify(events);
        assertThat(result.valid()).isFalse();
        assertThat(result.summary()).contains("belongs to run run-2");
    }

    @Test
    void theHashSurvivesAJsonRoundTripOfTheData() {
        // what a database-backed sink does: data written as JSON, read back as a generic map
        AuditEvent event = chain(1).getFirst();
        JsonCodec codec = JsonCodec.standard();
        Map<String, Object> reread = codec.mapper().readValue(codec.toJson(event.data()),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        AuditEvent restored = new AuditEvent(event.seq(), Instant.parse(event.timestamp().toString()), event.runId(),
                event.stageId(), event.type(), new Actor(event.actor().type(), event.actor().id()), event.message(),
                reread, event.prevHash(), event.hash());
        assertThat(AuditChain.hashOf(restored)).isEqualTo(event.hash());
        assertThat(AuditChain.verify(List.of(restored)).valid()).isTrue();
    }
}
