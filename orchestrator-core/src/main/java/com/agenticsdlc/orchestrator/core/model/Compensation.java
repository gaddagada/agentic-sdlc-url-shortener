package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A saga compensation registered by a stage for a side effect it produced (for example
 * {@code DELETE_SANDBOX <path>}). Executed in reverse order on rollback or abort.
 *
 * @param id compensation id
 * @param stageId registering stage
 * @param action action name understood by a compensation handler
 * @param target action target (for example a path)
 * @param description human-readable description
 * @param status status
 * @param registeredAt registration time
 * @param executedAt execution time
 * @param error failure message when execution failed
 */
public record Compensation(String id, String stageId, String action, String target, String description,
                           CompensationStatus status, Instant registeredAt, @Nullable Instant executedAt,
                           @Nullable String error) {

    /**
     * Normalizes text.
     */
    public Compensation {
        action = Immutables.text(action);
        target = Immutables.text(target);
        description = Immutables.text(description);
    }

    /**
     * Returns an executed copy.
     *
     * @param at execution time
     * @return the copy
     */
    public Compensation executed(Instant at) {
        return new Compensation(id, stageId, action, target, description, CompensationStatus.EXECUTED, registeredAt,
                at, null);
    }

    /**
     * Returns a failed copy.
     *
     * @param at execution time
     * @param message failure message
     * @return the copy
     */
    public Compensation failed(Instant at, String message) {
        return new Compensation(id, stageId, action, target, description, CompensationStatus.FAILED, registeredAt,
                at, message);
    }
}
