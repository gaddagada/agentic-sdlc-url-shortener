package com.agenticsdlc.orchestrator.core.engine;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Read-only state of the circuit breaker of one LLM provider (see {@link EngineConfig.ProviderCircuitBreaker}),
 * as returned by {@link OrchestrationEngine#providerCircuits()}.
 *
 * @param providerId provider id (as selected by {@code RunOptions.providerId})
 * @param state circuit state at the time of the snapshot
 * @param consecutiveFailures provider-attributable failures since the last success
 * @param openedAt when the circuit (last) opened, or null while it is closed
 * @param retryAt when an open circuit lets the next attempt try the provider again (half-open), or null while it
 *                is closed
 * @param lastFailure kind and message of the last counted failure (at most 300 characters), or null
 */
public record ProviderCircuit(String providerId, State state, int consecutiveFailures, @Nullable Instant openedAt,
                              @Nullable Instant retryAt, @Nullable String lastFailure) {

    /** Circuit states. */
    public enum State {
        /** Attempts call the provider. */
        CLOSED,
        /** Attempts run the deterministic agents until the cooldown has elapsed. */
        OPEN,
        /** The cooldown has elapsed: the next attempt tries the provider; its outcome closes or re-opens it. */
        HALF_OPEN
    }

    /**
     * Validates the snapshot.
     */
    public ProviderCircuit {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(state, "state");
    }
}
