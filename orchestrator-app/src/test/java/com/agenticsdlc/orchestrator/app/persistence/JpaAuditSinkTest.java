package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;

/**
 * {@link JpaAuditSink} on H2 with the Flyway schema, without a test-managed transaction (the engine appends from
 * its own threads).
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

        assertThatThrownBy(() -> sink.append(List.of(event("run-a", 1, AuditEventType.RUN_CREATED, null,
                Actor.system(), Map.of())))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sink.forRun("run-a", 0)).hasSize(1);
    }

    @Test
    void appendingNothingIsANoOp() {
        sink.append(List.of());

        assertThat(events.count()).isZero();
    }

    private static AuditEvent event(String runId, long seq, AuditEventType type, String stageId, Actor actor,
                                    Map<String, Object> data) {
        return new AuditEvent(seq, T0.plusSeconds(seq), runId, stageId, type, actor, type + " #" + seq, data);
    }
}
