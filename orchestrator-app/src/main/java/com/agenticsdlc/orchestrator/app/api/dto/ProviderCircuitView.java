package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.engine.ProviderCircuit;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Circuit-breaker state of an LLM provider: while the circuit is OPEN new attempts run the deterministic agents
 * instead of calling the provider, until {@code retryAt}; then it is HALF_OPEN and the next attempt tries the
 * provider again (success closes the circuit, failure re-opens it). Circuits are engine-wide and in memory.
 *
 * @param providerId provider id
 * @param state circuit state
 * @param consecutiveFailures provider-attributable failures since the last success
 * @param openedAt when the circuit (last) opened, null while closed
 * @param retryAt when an open circuit lets the next attempt try the provider again, null while closed
 * @param lastFailure kind and message of the last counted failure, credential-like tokens redacted and truncated to
 *                    {@value #MAX_FAILURE_TEXT} characters, or null
 */
@Schema(name = "ProviderCircuitView", description = "Circuit-breaker state of an LLM provider")
public record ProviderCircuitView(String providerId, State state, int consecutiveFailures,
                                  @Nullable Instant openedAt, @Nullable Instant retryAt,
                                  @Nullable String lastFailure) {

    /** Maximum length of {@code lastFailure}. */
    public static final int MAX_FAILURE_TEXT = 200;

    /** Replacement of a credential-like token in {@code lastFailure}. */
    static final String REDACTED = "[redacted]";

    /** Circuit states. */
    public enum State {
        /** Attempts call the provider. */
        CLOSED,
        /** Attempts run the deterministic agents until {@code retryAt}. */
        OPEN,
        /** The cooldown has elapsed: the next attempt tries the provider. */
        HALF_OPEN
    }

    /**
     * Maps the engine's circuit snapshot, redacting the failure text.
     *
     * @param circuit the snapshot
     * @return the view
     */
    public static ProviderCircuitView from(ProviderCircuit circuit) {
        return new ProviderCircuitView(circuit.providerId(), State.valueOf(circuit.state().name()),
                circuit.consecutiveFailures(), circuit.openedAt(), circuit.retryAt(),
                redact(circuit.lastFailure()));
    }

    /**
     * Replaces credential-like tokens ({@link SecretPatterns}) and truncates to {@value #MAX_FAILURE_TEXT}
     * characters.
     *
     * @param text failure text, or null
     * @return the safe text, or null
     */
    static @Nullable String redact(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String safe = text;
        for (Pattern pattern : SecretPatterns.PATTERNS) {
            safe = pattern.matcher(safe).replaceAll(REDACTED);
        }
        return safe.length() <= MAX_FAILURE_TEXT ? safe : safe.substring(0, MAX_FAILURE_TEXT - 1) + "…";
    }
}
