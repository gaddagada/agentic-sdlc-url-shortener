package com.agenticsdlc.orchestrator.core.engine;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.FailureKind;

/**
 * Engine-wide circuit breakers, one per LLM provider id, shared by all runs (thread-safe; every method is
 * synchronized, and none blocks). See {@link EngineConfig.ProviderCircuitBreaker} for the semantics.
 */
final class ProviderCircuits {

    /** Routing decision for an attempt that would call a provider agent. */
    enum Decision {
        /** The circuit is closed: call the provider. */
        CALL_PROVIDER,
        /** The cooldown has elapsed: this attempt is the half-open trial. */
        TRIAL,
        /** The circuit is open: run the deterministic agent instead. */
        FALLBACK
    }

    /** State change caused by an outcome. */
    enum Transition {
        /** No state change. */
        NONE,
        /** The failure threshold was reached. */
        OPENED,
        /** A failure while open or half-open (for example of the trial) re-opened the circuit. */
        REOPENED,
        /** A success closed an open or half-open circuit. */
        CLOSED
    }

    private static final int MAX_FAILURE_TEXT = 300;

    /** Mutable state of one provider, guarded by the enclosing instance. */
    private static final class Circuit {
        private int failures;
        private @Nullable Instant openedAt;
        private @Nullable Instant trialStartedAt;
        private @Nullable String lastFailure;
    }

    private final EngineConfig.ProviderCircuitBreaker settings;
    private final Map<String, Circuit> circuits = new HashMap<>();

    ProviderCircuits(EngineConfig.ProviderCircuitBreaker settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Returns whether a failure kind is provider-attributable (TRANSIENT, TIMEOUT, INVALID_OUTPUT).
     *
     * @param kind failure kind
     * @return true when it counts towards the breaker
     */
    static boolean counts(FailureKind kind) {
        return kind == FailureKind.TRANSIENT || kind == FailureKind.TIMEOUT || kind == FailureKind.INVALID_OUTPUT;
    }

    /**
     * Decides whether an attempt may call the provider. A half-open circuit admits one trial at a time; a trial
     * that never reports back stops blocking further trials after another cooldown.
     *
     * @param providerId provider id
     * @param now current time
     * @return the decision
     */
    synchronized Decision admit(String providerId, Instant now) {
        Circuit circuit = circuits.get(providerId);
        if (!settings.enabled() || circuit == null || circuit.openedAt == null) {
            return Decision.CALL_PROVIDER;
        }
        if (now.isBefore(circuit.openedAt.plus(settings.cooldown()))) {
            return Decision.FALLBACK;
        }
        if (circuit.trialStartedAt == null || !now.isBefore(circuit.trialStartedAt.plus(settings.cooldown()))) {
            circuit.trialStartedAt = now;
            return Decision.TRIAL;
        }
        return Decision.FALLBACK;
    }

    /**
     * Records a successful provider attempt: resets the failure count and closes the circuit.
     *
     * @param providerId provider id
     * @return CLOSED when the circuit was open or half-open, else NONE
     */
    synchronized Transition recordSuccess(String providerId) {
        Circuit circuit = circuits.get(providerId);
        if (circuit == null) {
            return Transition.NONE;
        }
        boolean wasOpen = circuit.openedAt != null;
        circuit.failures = 0;
        circuit.openedAt = null;
        circuit.trialStartedAt = null;
        circuit.lastFailure = null;
        return wasOpen ? Transition.CLOSED : Transition.NONE;
    }

    /**
     * Records a provider-attributable failure.
     *
     * @param providerId provider id
     * @param description failure description (kind and message)
     * @param now current time
     * @return OPENED when the threshold was reached, REOPENED when the circuit was already open or half-open,
     *         else NONE
     */
    synchronized Transition recordFailure(String providerId, String description, Instant now) {
        Circuit circuit = circuits.computeIfAbsent(providerId, k -> new Circuit());
        circuit.failures++;
        circuit.lastFailure = description.length() <= MAX_FAILURE_TEXT ? description
                : description.substring(0, MAX_FAILURE_TEXT - 3) + "...";
        if (!settings.enabled()) {
            return Transition.NONE;
        }
        if (circuit.openedAt != null) {
            circuit.openedAt = now;
            circuit.trialStartedAt = null;
            return Transition.REOPENED;
        }
        if (circuit.failures >= settings.failureThreshold()) {
            circuit.openedAt = now;
            circuit.trialStartedAt = null;
            return Transition.OPENED;
        }
        return Transition.NONE;
    }

    /**
     * Records an attempt that says nothing about provider health (a FATAL failure, or a cancelled attempt): ends a
     * half-open trial without a verdict, so that the next attempt may try again.
     *
     * @param providerId provider id
     */
    synchronized void recordInconclusive(String providerId) {
        Circuit circuit = circuits.get(providerId);
        if (circuit != null) {
            circuit.trialStartedAt = null;
        }
    }

    /**
     * Returns the state of one provider.
     *
     * @param providerId provider id
     * @param now current time
     * @return the snapshot (CLOSED with no failures for an unknown provider)
     */
    synchronized ProviderCircuit circuit(String providerId, Instant now) {
        Circuit circuit = circuits.get(providerId);
        if (circuit == null) {
            return new ProviderCircuit(providerId, ProviderCircuit.State.CLOSED, 0, null, null, null);
        }
        ProviderCircuit.State state;
        Instant retryAt = null;
        if (circuit.openedAt == null) {
            state = ProviderCircuit.State.CLOSED;
        } else {
            retryAt = circuit.openedAt.plus(settings.cooldown());
            state = now.isBefore(retryAt) ? ProviderCircuit.State.OPEN : ProviderCircuit.State.HALF_OPEN;
        }
        return new ProviderCircuit(providerId, state, circuit.failures, circuit.openedAt, retryAt,
                circuit.lastFailure);
    }

    /**
     * Returns the states of the given providers and of every provider with recorded failures.
     *
     * @param providerIds provider ids to include even without history
     * @param now current time
     * @return provider id to state, sorted by id (unmodifiable)
     */
    synchronized SortedMap<String, ProviderCircuit> snapshot(Collection<String> providerIds, Instant now) {
        SortedMap<String, ProviderCircuit> result = new TreeMap<>();
        for (String id : providerIds) {
            result.put(id, circuit(id, now));
        }
        for (String id : circuits.keySet()) {
            result.put(id, circuit(id, now));
        }
        return Collections.unmodifiableSortedMap(result);
    }
}
