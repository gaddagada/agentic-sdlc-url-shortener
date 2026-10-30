package com.agenticsdlc.orchestrator.app.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;

/**
 * Knows every agent provider the control plane can offer: the built-in deterministic agents, the registered LLM
 * providers, and the known LLM providers that are disabled by configuration. Resolves the provider of a new run
 * and refuses providers that cannot serve it, so a run never starts only to fall back immediately.
 */
public final class ProviderCatalog {

    private static final String DETERMINISTIC_MODEL = "rule-based";

    private final Map<String, ProviderClient> clients = new LinkedHashMap<>();
    private final LlmProperties properties;
    private final String defaultProvider;

    /**
     * Creates the catalog.
     *
     * @param registered the registered (enabled) LLM providers
     * @param properties LLM configuration (used to describe disabled providers)
     * @param defaultProvider provider used when a request names none
     * @throws IllegalStateException when the default provider is unknown or disabled
     */
    public ProviderCatalog(List<? extends ProviderClient> registered, LlmProperties properties,
                           String defaultProvider) {
        registered.forEach(c -> clients.put(c.providerId(), c));
        this.properties = properties;
        this.defaultProvider = normalize(defaultProvider);
        if (!isRegistered(this.defaultProvider)) {
            throw new IllegalStateException("sdlc.agents.default-provider '" + defaultProvider
                    + "' is not a registered provider; registered: " + registeredIds());
        }
    }

    /**
     * Returns the default provider id.
     *
     * @return the provider id
     */
    public String defaultProvider() {
        return defaultProvider;
    }

    /**
     * Returns the status of every known provider: deterministic first, then the LLM providers.
     *
     * @return statuses
     */
    public List<ProviderStatus> providers() {
        List<ProviderStatus> statuses = new ArrayList<>();
        statuses.add(new ProviderStatus(AgentRegistry.DETERMINISTIC, true, DETERMINISTIC_MODEL,
                "built-in deterministic agents (always available; also the fallback of every stage)"));
        clients.values().forEach(c -> statuses.add(new ProviderStatus(c.providerId(), c.isAvailable(), c.model(),
                c.detail())));
        if (!clients.containsKey(AnthropicLlmClient.PROVIDER_ID)) {
            statuses.add(new ProviderStatus(AnthropicLlmClient.PROVIDER_ID, false, properties.anthropic().model(),
                    "disabled by configuration (sdlc.llm.anthropic.enabled=false)"));
        }
        if (!clients.containsKey(OllamaLlmClient.PROVIDER_ID)) {
            statuses.add(new ProviderStatus(OllamaLlmClient.PROVIDER_ID, false, properties.ollama().model(),
                    "disabled by configuration (sdlc.llm.ollama.enabled=false)"));
        }
        return List.copyOf(statuses);
    }

    /**
     * Resolves the provider of a new run and checks that it can serve it.
     *
     * @param requested requested provider id, or null/blank for the default
     * @return the normalized provider id
     * @throws ProviderUnavailableException when the provider is unknown, disabled or unavailable
     */
    public String resolveAvailable(@Nullable String requested) {
        String id = requested == null || requested.isBlank() ? defaultProvider : normalize(requested);
        if (AgentRegistry.DETERMINISTIC.equals(id)) {
            return id;
        }
        ProviderClient client = clients.get(id);
        if (client == null) {
            boolean known = AnthropicLlmClient.PROVIDER_ID.equals(id) || OllamaLlmClient.PROVIDER_ID.equals(id);
            throw new ProviderUnavailableException(id, known ? "disabled by configuration"
                    : "unknown provider; registered providers: " + registeredIds());
        }
        if (!client.isAvailable()) {
            throw new ProviderUnavailableException(id, client.detail());
        }
        return id;
    }

    private boolean isRegistered(String id) {
        return AgentRegistry.DETERMINISTIC.equals(id) || clients.containsKey(id);
    }

    private List<String> registeredIds() {
        List<String> ids = new ArrayList<>();
        ids.add(AgentRegistry.DETERMINISTIC);
        ids.addAll(clients.keySet());
        return ids;
    }

    private static String normalize(String id) {
        return id.strip().toLowerCase(Locale.ROOT);
    }
}
