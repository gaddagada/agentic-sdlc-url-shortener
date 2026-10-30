package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * State captured when a stage activation starts, used by rollback to restore a consistent state.
 *
 * @param id checkpoint id
 * @param stageId stage whose activation created the checkpoint
 * @param planVersion plan version at that moment
 * @param acceptedVersions accepted artifact versions at that moment
 * @param compensationIds ids of compensations registered before that moment
 * @param createdAt creation time
 */
public record Checkpoint(String id, String stageId, int planVersion, Map<String, Integer> acceptedVersions,
                         List<String> compensationIds, Instant createdAt) {

    /**
     * Copies collections.
     */
    public Checkpoint {
        acceptedVersions = Immutables.map(acceptedVersions);
        compensationIds = Immutables.list(compensationIds);
    }
}
