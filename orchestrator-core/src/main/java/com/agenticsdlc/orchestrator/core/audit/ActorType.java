package com.agenticsdlc.orchestrator.core.audit;

/**
 * Kind of actor behind an audit event.
 */
public enum ActorType {
    /** The engine. */
    SYSTEM,
    /** A stage agent. */
    AGENT,
    /** A human operator (identified by operator id). */
    HUMAN;
}
