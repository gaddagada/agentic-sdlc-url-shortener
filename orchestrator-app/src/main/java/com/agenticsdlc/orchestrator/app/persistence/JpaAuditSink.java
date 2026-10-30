package com.agenticsdlc.orchestrator.app.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.orchestrator.app.runs.RunConflictException;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.ActorType;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

import tools.jackson.core.type.TypeReference;

/**
 * JPA implementation of the engine's append-only {@link AuditSink}. The engine appends the events of one command in
 * a single batch while holding the run lock, from threads outside any Spring transaction, so transactions are
 * demarcated explicitly with a {@link TransactionTemplate} (propagation REQUIRED: when {@link JpaRunStore} commits a
 * mutation, the append joins the transaction that saves the run snapshot, so both are atomic).
 *
 * <p>The per-run hash chain ({@code prev_hash}, {@code hash}) is stored with every event, and every hashed field
 * round-trips verbatim, so {@code AuditChain.verify(forRun(runId, 0))} succeeds for an untampered log. Inside the
 * append transaction the sink enforces chain continuity like the core's {@code InMemoryAuditSink}: a batch whose
 * first {@code prevHash} does not continue the stored head of the run, or that is not itself an intact chain, is
 * rejected with an {@link IllegalStateException} and nothing of the commit is persisted (legacy unsigned batches
 * remain accepted). The unique {@code (run_id, seq)} constraint still guards against concurrent writers. A batch
 * refused because another writer appended first (it does not continue the stored head, or its sequence numbers are
 * taken) is a {@link RunConflictException}.</p>
 */
@Component
public class JpaAuditSink implements AuditSink {

    private static final TypeReference<LinkedHashMap<String, Object>> DATA_TYPE = new TypeReference<>() {
    };

    private final AuditEventRepository events;
    private final JsonCodec codec;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readTransactions;

    /**
     * Creates the sink.
     *
     * @param events audit event repository
     * @param codec JSON codec
     * @param transactionManager transaction manager
     */
    public JpaAuditSink(AuditEventRepository events, JsonCodec codec, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.codec = codec;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    /**
     * Appends events after checking, per run and inside the transaction, that they continue the run's stored hash
     * chain.
     *
     * @param batch events to append, in sequence order per run (an engine batch always belongs to one run)
     * @throws RunConflictException when another writer appended to a run first (the batch does not continue the
     *                              stored chain, or its sequence numbers are taken)
     * @throws IllegalStateException when a run's events are not an intact chain
     */
    @Override
    public void append(List<AuditEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Map<String, List<AuditEvent>> byRun = new LinkedHashMap<>();
        for (AuditEvent event : batch) {
            byRun.computeIfAbsent(event.runId(), k -> new ArrayList<>()).add(event);
        }
        List<AuditEventEntity> rows = batch.stream().map(this::toEntity).toList();
        transactions.executeWithoutResult(status -> {
            byRun.forEach((runId, runEvents) -> AuditChainContinuity.check(runId,
                    events.findFirstByRunIdOrderBySeqDesc(runId).map(this::toEvent).orElse(null), runEvents));
            try {
                events.saveAll(rows);
            } catch (DataIntegrityViolationException e) {
                // the unique (run_id, seq) constraint: a concurrent writer stored these sequence numbers first
                throw new RunConflictException("audit events of run(s) " + byRun.keySet() + " were refused: their "
                        + "sequence numbers were stored by another writer first", e);
            }
        });
    }

    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq) {
        List<AuditEventEntity> rows = readTransactions.execute(
                status -> events.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, afterSeq));
        return rows == null ? List.of() : rows.stream().map(this::toEvent).toList();
    }

    /**
     * Returns a page of events with the limit applied by the database query.
     *
     * @param runId run id
     * @param afterSeq exclusive lower bound (0 for all)
     * @param limit maximum number of events (positive)
     * @return at most {@code limit} events in sequence order
     */
    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<AuditEventEntity> rows = readTransactions.execute(
                status -> events.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, afterSeq, Limit.of(limit)));
        return rows == null ? List.of() : rows.stream().map(this::toEvent).toList();
    }

    /**
     * Returns the last stored event of a run with a bounded query ({@code ORDER BY seq DESC}, one row).
     *
     * @param runId run id
     * @return the last event, or empty when the run has no events
     */
    @Override
    public Optional<AuditEvent> lastEvent(String runId) {
        Optional<AuditEventEntity> row = readTransactions.execute(
                status -> events.findFirstByRunIdOrderBySeqDesc(runId));
        return row == null ? Optional.empty() : row.map(this::toEvent);
    }

    private AuditEventEntity toEntity(AuditEvent event) {
        String data = event.data().isEmpty() ? null : codec.toJson(event.data());
        return new AuditEventEntity(event.runId(), event.seq(), event.timestamp(), event.stageId(),
                event.type().name(), event.actor().type().name(), event.actor().id(), event.message(), data,
                event.prevHash(), event.hash());
    }

    private AuditEvent toEvent(AuditEventEntity row) {
        Map<String, Object> data = row.getDataJson() == null ? Map.of()
                : codec.mapper().readValue(row.getDataJson(), DATA_TYPE);
        return new AuditEvent(row.getSeq(), row.getTimestamp(), row.getRunId(), row.getStageId(),
                AuditEventType.valueOf(row.getType()),
                new Actor(ActorType.valueOf(row.getActorType()), row.getActorId()), row.getMessage(), data,
                row.getPrevHash(), row.getHash());
    }
}
