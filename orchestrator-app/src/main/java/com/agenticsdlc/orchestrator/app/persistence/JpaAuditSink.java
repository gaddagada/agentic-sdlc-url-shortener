package com.agenticsdlc.orchestrator.app.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * demarcated explicitly with a {@link TransactionTemplate}.
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

    @Override
    public void append(List<AuditEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<AuditEventEntity> rows = batch.stream().map(this::toEntity).toList();
        transactions.executeWithoutResult(status -> events.saveAll(rows));
    }

    @Override
    public List<AuditEvent> forRun(String runId, long afterSeq) {
        List<AuditEventEntity> rows = readTransactions.execute(
                status -> events.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, afterSeq));
        return rows == null ? List.of() : rows.stream().map(this::toEvent).toList();
    }

    private AuditEventEntity toEntity(AuditEvent event) {
        String data = event.data().isEmpty() ? null : codec.toJson(event.data());
        return new AuditEventEntity(event.runId(), event.seq(), event.timestamp(), event.stageId(),
                event.type().name(), event.actor().type().name(), event.actor().id(), event.message(), data);
    }

    private AuditEvent toEvent(AuditEventEntity row) {
        Map<String, Object> data = row.getDataJson() == null ? Map.of()
                : codec.mapper().readValue(row.getDataJson(), DATA_TYPE);
        return new AuditEvent(row.getSeq(), row.getTimestamp(), row.getRunId(), row.getStageId(),
                AuditEventType.valueOf(row.getType()),
                new Actor(ActorType.valueOf(row.getActorType()), row.getActorId()), row.getMessage(), data);
    }
}
