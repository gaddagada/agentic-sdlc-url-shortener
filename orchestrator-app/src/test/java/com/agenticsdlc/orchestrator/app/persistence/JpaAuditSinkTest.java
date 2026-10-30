package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.orchestrator.app.runs.RunConflictException;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.ChainVerification;

/**
 * {@link JpaAuditSink} on H2 with the Flyway schema, without a test-managed transaction (the engine appends from
 * its own threads): ordering, paging and truncation of legacy events, and the hash chain (every hashed field
 * round-trips so a stored chain verifies, a tampered row is reported at its sequence number, and a batch that does
 * not continue the stored chain is rejected inside the transaction).
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({JpaAuditSink.class, PersistenceFixtures.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaAuditSinkTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private JpaAuditSink sink;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        events.deleteAll();
    }

    @Test
    void appendedEventsAreReturnedInSequenceOrderWithTheirData() {
        sink.append(List.of(
                event("run-a", 2, AuditEventType.STAGE_STARTED, "intake", Actor.agent("deterministic"),
                        Map.of("attempt", 1, "derivedFrom", Map.of())),
                event("run-a", 1, AuditEventType.RUN_CREATED, null, Actor.system(), Map.of("scenario", "greenfield",
                        "faults", List.of("implement:TRANSIENTx2")))));
        sink.append(List.of(event("run-a", 3, AuditEventType.APPROVAL_GRANTED, "design", Actor.human("alice"),
                Map.of("comment", "ok", "decisionMs", 1234L))));
        sink.append(List.of(event("run-b", 1, AuditEventType.RUN_CREATED, null, Actor.system(), Map.of())));

        List<AuditEvent> all = sink.forRun("run-a", 0);

        assertThat(all).extracting(AuditEvent::seq).containsExactly(1L, 2L, 3L);
        AuditEvent created = all.getFirst();
        assertThat(created.type()).isEqualTo(AuditEventType.RUN_CREATED);
        assertThat(created.actor()).isEqualTo(Actor.system());
        assertThat(created.stageId()).isNull();
        assertThat(created.timestamp()).isEqualTo(T0.plusSeconds(1));
        assertThat(created.data()).containsEntry("scenario", "greenfield")
                .containsEntry("faults", List.of("implement:TRANSIENTx2"));
        AuditEvent granted = all.get(2);
        assertThat(granted.actor()).isEqualTo(Actor.human("alice"));
        assertThat(granted.stageId()).isEqualTo("design");
        assertThat(((Number) granted.data().get("decisionMs")).longValue()).isEqualTo(1234L);
    }

    @Test
    void afterSeqIsExclusiveAndRunsAreIsolated() {
        sink.append(List.of(
                event("run-a", 1, AuditEventType.RUN_CREATED, null, Actor.system(), Map.of()),
                event("run-a", 2, AuditEventType.STAGE_READY, "intake", Actor.system(), Map.of()),
                event("run-b", 1, AuditEventType.RUN_CREATED, null, Actor.system(), Map.of())));

        assertThat(sink.forRun("run-a", 1)).extracting(AuditEvent::seq).containsExactly(2L);
        assertThat(sink.forRun("run-a", 2)).isEmpty();
        assertThat(sink.forRun("run-b", 0)).hasSize(1);
        assertThat(sink.forRun("unknown", 0)).isEmpty();
        assertThat(events.findAll()).filteredOn(e -> e.getRunId().equals("run-a"))
                .allSatisfy(e -> assertThat(e.getDataJson()).isNull());
    }

    @Test
    void pagesAreLimitedByTheDatabaseQuery() {
        List<AuditEvent> batch = new ArrayList<>();
        for (long seq = 1; seq <= 6; seq++) {
            batch.add(event("run-a", seq, AuditEventType.STAGE_READY, "intake", Actor.system(), Map.of()));
        }
        sink.append(batch);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<AuditEvent> page = sink.forRun("run-a", 1, 2);

        assertThat(page).extracting(AuditEvent::seq).containsExactly(2L, 3L);
        assertThat(statistics.getEntityLoadCount()).as("rows read from the database").isEqualTo(2L);
        assertThat(sink.forRun("run-a", 3, 10)).extracting(AuditEvent::seq).containsExactly(4L, 5L, 6L);
        assertThat(sink.forRun("run-a", 6, 10)).isEmpty();
        assertThatThrownBy(() -> sink.forRun("run-a", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overLongMessagesAreTruncatedToTheColumn() {
        sink.append(List.of(new AuditEvent(1, T0, "run-a", null, AuditEventType.RUN_CREATED, Actor.system(),
                "m".repeat(5000), Map.of())));

        assertThat(sink.forRun("run-a", 0).getFirst().message()).hasSize(AuditEventEntity.MESSAGE_MAX)
                .endsWith("…");
    }

    @Test
    void theSequenceIsUniquePerRun() {
        sink.append(List.of(event("run-a", 1, AuditEventType.RUN_CREATED, null, Actor.system(), Map.of())));

        // another writer stored this sequence number first: a conflict, with the constraint violation as its cause
        assertThatThrownBy(() -> sink.append(List.of(event("run-a", 1, AuditEventType.RUN_CREATED, null,
                Actor.system(), Map.of())))).isInstanceOf(RunConflictException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("run-a");
        assertThat(sink.forRun("run-a", 0)).hasSize(1);
    }

    @Test
    void appendingNothingIsANoOp() {
        sink.append(List.of());

        assertThat(events.count()).isZero();
    }

    @Test
    void aCommittedChainVerifiesAfterTheRoundTripThroughTheDatabase() {
        List<AuditEvent> first = chain("run-c", 1, 3, null);
        List<AuditEvent> second = chain("run-c", 4, 6, first.getLast().hash());
        sink.append(first);
        sink.append(second);

        List<AuditEvent> stored = sink.forRun("run-c", 0);

        assertThat(stored).hasSize(6);
        ChainVerification verification = AuditChain.verify(stored);
        assertThat(verification.valid()).as(verification.summary()).isTrue();
        assertThat(verification.length()).isEqualTo(6);
        for (int i = 0; i < stored.size(); i++) {
            AuditEvent written = i < 3 ? first.get(i) : second.get(i - 3);
            assertThat(stored.get(i).hash()).isEqualTo(written.hash());
            assertThat(stored.get(i).prevHash()).isEqualTo(written.prevHash());
            assertThat(AuditChain.canonicalForm(stored.get(i))).isEqualTo(AuditChain.canonicalForm(written));
        }
        assertThat(sink.lastEvent("run-c")).hasValueSatisfying(last -> {
            assertThat(last.seq()).isEqualTo(6);
            assertThat(last.hash()).isEqualTo(second.getLast().hash());
        });
        assertThat(sink.lastEvent("unknown")).isEmpty();
    }

    @Test
    void aTamperedRowIsReportedAtItsSequenceNumber() {
        List<AuditEvent> written = chain("run-t", 1, 5, null);
        sink.append(written);

        int updated = JdbcClient.create(dataSource)
                .sql("UPDATE audit_events SET message = ? WHERE run_id = ? AND seq = ?")
                .params("approved by mallory", "run-t", 3).update();

        assertThat(updated).isOne();
        ChainVerification verification = AuditChain.verify(sink.forRun("run-t", 0));
        assertThat(verification.valid()).isFalse();
        assertThat(verification.brokenAtSeq()).isEqualTo(3L);
        assertThat(verification.length()).isEqualTo(2);
        assertThat(verification.actualHash()).isEqualTo(written.get(2).hash());
        assertThat(verification.summary()).contains("seq 3");
    }

    @Test
    void aBatchThatDoesNotContinueTheStoredChainIsRejectedAndNothingIsStored() {
        List<AuditEvent> stored = chain("run-x", 1, 2, null);
        sink.append(stored);

        List<AuditEvent> forked = chain("run-x", 3, 4, stored.getFirst().hash());
        // not continuing the stored head means another writer appended first: a run conflict
        assertThatThrownBy(() -> sink.append(forked)).isInstanceOf(RunConflictException.class)
                .hasMessageContaining("does not continue the stored hash chain");
        List<AuditEvent> gap = chain("run-x", 4, 5, stored.getLast().hash());
        assertThatThrownBy(() -> sink.append(gap)).isInstanceOf(RunConflictException.class)
                .hasMessageContaining("does not follow seq 2");
        // a batch broken in itself is an integrity error, not a conflict
        AuditEvent good = chain("run-x", 3, 3, stored.getLast().hash()).getFirst();
        AuditEvent forged = good.withChain(good.prevHash(), AuditChain.GENESIS);
        assertThatThrownBy(() -> sink.append(List.of(forged))).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(RunConflictException.class).hasMessageContaining("content hashes to");
        AuditEvent unsigned = event("run-x", 3, AuditEventType.STAGE_READY, null, Actor.system(), Map.of());
        assertThatThrownBy(() -> sink.append(List.of(unsigned))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("would break the run's hash chain");

        assertThat(sink.forRun("run-x", 0)).extracting(AuditEvent::seq).containsExactly(1L, 2L);
        sink.append(List.of(good));
        assertThat(AuditChain.verify(sink.forRun("run-x", 0)).valid()).isTrue();
    }

    @Test
    void legacyUnsignedEventsStayAcceptedAndASignedEventStartsANewChainAfterThem() {
        sink.append(List.of(event("run-l", 1, AuditEventType.RUN_CREATED, null, Actor.system(), Map.of())));
        sink.append(List.of(event("run-l", 2, AuditEventType.STAGE_READY, "intake", Actor.system(), Map.of())));
        AuditEvent signed = AuditChain.link(new AuditEvent(3, T0.plusSeconds(3), "run-l", null,
                AuditEventType.RUN_RESUMED, Actor.human("alice"), "resumed", Map.of()), null);

        sink.append(List.of(signed));

        List<AuditEvent> stored = sink.forRun("run-l", 0);
        assertThat(stored).extracting(AuditEvent::signed).containsExactly(false, false, true);
        ChainVerification verification = AuditChain.verify(stored);
        assertThat(verification.valid()).as("legacy events make the chain unsigned").isFalse();
        assertThat(verification.brokenAtSeq()).isEqualTo(1L);
        assertThat(verification.summary()).contains("unsigned");
    }

    @Test
    void aSignedEventIsNeverTruncatedOrRounded() {
        AuditEvent tooLong = AuditChain.link(new AuditEvent(1, T0, "run-v", null, AuditEventType.RUN_CREATED,
                Actor.system(), "m".repeat(AuditEventEntity.MESSAGE_MAX + 1), Map.of()), null);
        AuditEvent nanos = AuditChain.link(new AuditEvent(1, T0.plusNanos(1), "run-v", null,
                AuditEventType.RUN_CREATED, Actor.system(), "created", Map.of()), null);

        assertThatThrownBy(() -> sink.append(List.of(tooLong))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would break the chain");
        assertThatThrownBy(() -> sink.append(List.of(nanos))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub-microsecond");
        assertThat(events.count()).isZero();
    }

    /**
     * Links events {@code from..to} of a run, continuing {@code prevHash}, with data of every JSON shape the engine
     * writes (strings, integers, longs, decimals, booleans, lists, nested maps), a non-ASCII message, a message at
     * the column limit and millisecond timestamps.
     */
    private static List<AuditEvent> chain(String runId, long from, long to, String prevHash) {
        List<AuditEvent> linked = new ArrayList<>();
        String previous = prevHash;
        for (long seq = from; seq <= to; seq++) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attempt", (int) seq);
            data.put("latencyMs", 1_234_567_890_123L + seq);
            data.put("score", 0.25 * seq);
            data.put("passed", seq % 2 == 0);
            data.put("stages", List.of("intake", "design"));
            data.put("derivedFrom", Map.of("intake", 1, "impact", 2));
            data.put("nodeId", "node-a");
            String message = seq == 2 ? "x".repeat(AuditEventEntity.MESSAGE_MAX)
                    : "Ereignis #" + seq + " \u00e4\u00f6\u00fc \u2014 \u2713";
            AuditEvent event = new AuditEvent(seq, T0.plusMillis(seq * 1001), runId, seq == 1 ? null : "design",
                    AuditEventType.values()[(int) seq % AuditEventType.values().length],
                    seq % 3 == 0 ? Actor.human("alice@example.com") : Actor.agent("deterministic"), message, data);
            AuditEvent signed = AuditChain.link(event, previous);
            linked.add(signed);
            previous = signed.hash();
        }
        return linked;
    }

    private static AuditEvent event(String runId, long seq, AuditEventType type, String stageId, Actor actor,
                                    Map<String, Object> data) {
        return new AuditEvent(seq, T0.plusSeconds(seq), runId, stageId, type, actor, type + " #" + seq, data);
    }
}
