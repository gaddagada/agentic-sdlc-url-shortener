package com.agenticsdlc.orchestrator.core.graph;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;

/**
 * A stage of the workflow graph.
 *
 * @param id stable stage id, for example {@code intake}
 * @param name human-readable name
 * @param role agent role executing the stage
 * @param dependsOn predecessor stage ids (insertion ordered)
 * @param gates entry/exit gate behaviour
 * @param retryPolicy bounded retry policy (attempts include the first)
 * @param timeout timeout per attempt
 * @param fallbackToDeterministic whether the deterministic agent takes over when the primary agent's
 *                                attempts are exhausted
 */
public record StageDefinition(
        String id,
        String name,
        AgentRole role,
        Set<String> dependsOn,
        GateSpec gates,
        RetryPolicy retryPolicy,
        Duration timeout,
        boolean fallbackToDeterministic) {

    /**
     * Validates the definition and makes {@code dependsOn} an immutable, order-preserving set.
     */
    public StageDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(gates, "gates");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(timeout, "timeout");
        if (!id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("stage id must match [a-z0-9][a-z0-9-]{0,63}: " + id);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("stage timeout must be positive: " + id);
        }
        dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(dependsOn == null ? Set.of() : dependsOn));
    }

    /**
     * Returns a copy with a different fallback flag.
     *
     * @param fallback whether to fall back to the deterministic agent
     * @return the copy
     */
    public StageDefinition withFallbackToDeterministic(boolean fallback) {
        return new StageDefinition(id, name, role, dependsOn, gates, retryPolicy, timeout, fallback);
    }

    /**
     * Returns a copy with a different timeout.
     *
     * @param newTimeout timeout per attempt
     * @return the copy
     */
    public StageDefinition withTimeout(Duration newTimeout) {
        return new StageDefinition(id, name, role, dependsOn, gates, retryPolicy, newTimeout, fallbackToDeterministic);
    }

    /**
     * Returns a copy with a different retry policy.
     *
     * @param policy retry policy
     * @return the copy
     */
    public StageDefinition withRetryPolicy(RetryPolicy policy) {
        return new StageDefinition(id, name, role, dependsOn, gates, policy, timeout, fallbackToDeterministic);
    }

    /**
     * Returns a copy with different gates.
     *
     * @param newGates gates
     * @return the copy
     */
    public StageDefinition withGates(GateSpec newGates) {
        return new StageDefinition(id, name, role, dependsOn, newGates, retryPolicy, timeout, fallbackToDeterministic);
    }
}
