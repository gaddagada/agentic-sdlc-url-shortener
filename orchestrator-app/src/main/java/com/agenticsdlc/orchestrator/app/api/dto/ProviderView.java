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
 */
public record ProviderView(String id, boolean available, @Nullable String model, String detail) {

    /**
     * Maps a provider status.
     *
     * @param status the status
     * @return the view
     */
    public static ProviderView from(ProviderStatus status) {
        return new ProviderView(status.id(), status.available(), status.model(), status.detail());
    }
}
