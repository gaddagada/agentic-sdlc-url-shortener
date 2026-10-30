package com.agenticsdlc.orchestrator.core.audit;

import java.util.Objects;

/**
 * Who performed an audited action. Governance actions carry the human operator id.
 *
 * @param type actor type
 * @param id actor id (operator id, agent id or {@code engine})
 */
public record Actor(ActorType type, String id) {

    private static final Actor SYSTEM = new Actor(ActorType.SYSTEM, "engine");

    /**
     * Validates the actor.
     */
    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("actor id must not be blank");
        }
    }

    /**
     * Returns the engine actor.
     *
     * @return the system actor
     */
    public static Actor system() {
        return SYSTEM;
    }

    /**
     * Returns an agent actor.
     *
     * @param agentId agent id
     * @return the actor
     */
    public static Actor agent(String agentId) {
        return new Actor(ActorType.AGENT, agentId);
    }

    /**
     * Returns a human actor.
     *
     * @param operatorId operator id
     * @return the actor
     */
    public static Actor human(String operatorId) {
        return new Actor(ActorType.HUMAN, operatorId);
    }
}
