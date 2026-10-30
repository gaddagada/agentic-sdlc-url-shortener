package com.agenticsdlc.orchestrator.app.llm;

import org.jspecify.annotations.Nullable;

/**
 * Availability of one agent provider.
 *
 * @param id provider id ({@code deterministic}, {@code anthropic}, {@code ollama})
 * @param available whether new runs can use it
 * @param model model name, or null
 * @param detail human-readable detail (never contains secrets)
 */
public record ProviderStatus(String id, boolean available, @Nullable String model, String detail) {
}
