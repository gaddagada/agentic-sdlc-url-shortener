package com.agenticsdlc.orchestrator.app.api.dto;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.app.llm.ProviderStatus;

/**
 * Availability of an agent provider.
 *
 * @param id provider id
 * @param available whether new runs can use it
 * @param model model name
 * @param detail human-readable detail
 * @param circuit circuit-breaker state of an LLM provider registered with the engine; null for the deterministic
 *                agents and for providers disabled by configuration
 */
public record ProviderView(String id, boolean available, @Nullable String model, String detail,
                           @Nullable ProviderCircuitView circuit) {

    /**
     * Maps a provider status without circuit state.
     *
     * @param status the status
     * @return the view
     */
    public static ProviderView from(ProviderStatus status) {
        return from(status, null);
    }

    /**
     * Maps a provider status and its circuit.
     *
     * @param status the status
     * @param circuit the provider's circuit, or null
     * @return the view
     */
    public static ProviderView from(ProviderStatus status, @Nullable ProviderCircuitView circuit) {
        return new ProviderView(status.id(), status.available(), status.model(), status.detail(), circuit);
    }
}
