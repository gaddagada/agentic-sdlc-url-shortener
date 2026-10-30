package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository of {@link AuditEventEntity}.
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    /**
     * Returns the events of a run after a sequence number, in sequence order.
     *
     * @param runId run id
     * @param seq exclusive lower bound of the sequence number
     * @return events
     */
    List<AuditEventEntity> findByRunIdAndSeqGreaterThanOrderBySeqAsc(String runId, long seq);

    /**
     * Returns at most {@code limit} events of a run after a sequence number, in sequence order (the limit is
     * applied by the database).
     *
     * @param runId run id
     * @param seq exclusive lower bound of the sequence number
     * @param limit maximum number of rows
     * @return events
     */
    List<AuditEventEntity> findByRunIdAndSeqGreaterThanOrderBySeqAsc(String runId, long seq, Limit limit);

    /**
     * Returns the last event of a run (highest sequence number), the head of its hash chain, with a bounded query
     * ({@code ORDER BY seq DESC} limited to one row).
     *
     * @param runId run id
     * @return the last event, or empty when the run has no events
     */
    Optional<AuditEventEntity> findFirstByRunIdOrderBySeqDesc(String runId);
}

