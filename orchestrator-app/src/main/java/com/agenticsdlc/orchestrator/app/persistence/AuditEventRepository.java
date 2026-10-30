package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;

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
}
