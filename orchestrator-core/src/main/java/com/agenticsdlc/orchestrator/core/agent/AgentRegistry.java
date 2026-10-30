package com.agenticsdlc.orchestrator.core.agent;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Maps agent roles to agents per provider. The deterministic agents are mandatory (they are the default
 * provider and the fallback); LLM providers are optional and may cover a subset of roles (missing roles
 * use the deterministic agent).
 *
 * <pre>{@code
 * AgentRegistry registry = AgentRegistry.builder()
 *         .deterministic(DeterministicAgents.all(new ProcessBuildRunner()))
 *         .provider("anthropic", LlmStageAgent.forAllRoles(anthropicClient, codec))
 *         .build();
 * }</pre>
 */
public final class AgentRegistry {

    /** Provider id of the deterministic agents. */
    public static final String DETERMINISTIC = "deterministic";

    /** Agent id used when the deterministic agent runs as fallback. */
    public static final String FALLBACK_AGENT_ID = "fallback:deterministic";

    private final Map<AgentRole, StageAgent> deterministicAgents;
    private final Map<String, Map<AgentRole, StageAgent>> providers;

    private AgentRegistry(Map<AgentRole, StageAgent> deterministicAgents,
                          Map<String, Map<AgentRole, StageAgent>> providers) {
        this.deterministicAgents = Collections.unmodifiableMap(new EnumMap<>(deterministicAgents));
        Map<String, Map<AgentRole, StageAgent>> copy = new LinkedHashMap<>();
        providers.forEach((id, agents) -> copy.put(id, Collections.unmodifiableMap(new EnumMap<>(agents))));
        this.providers = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a registry with deterministic agents only.
     *
     * @param deterministicAgents one agent per role
     * @return the registry
     */
    public static AgentRegistry of(Collection<? extends StageAgent> deterministicAgents) {
        return builder().deterministic(deterministicAgents).build();
    }

    /**
     * Returns the agent for a role and provider. A null provider or {@code deterministic} selects the
     * deterministic agent; a provider without an agent for the role also falls back to it.
     *
     * @param role role
     * @param providerId provider id or null
     * @return the agent
     * @throws IllegalArgumentException when the provider is unknown or the role has no agent
     */
    public StageAgent agentFor(AgentRole role, @Nullable String providerId) {
        if (providerId == null || providerId.isBlank() || DETERMINISTIC.equals(providerId)) {
            return deterministic(role);
        }
        Map<AgentRole, StageAgent> agents = providers.get(providerId);
        if (agents == null) {
            throw new IllegalArgumentException("Unknown agent provider: " + providerId);
        }
        StageAgent agent = agents.get(role);
        return agent != null ? agent : deterministic(role);
    }

    /**
     * Returns the deterministic agent for a role.
     *
     * @param role role
     * @return the agent
     * @throws IllegalArgumentException when no deterministic agent is registered for the role
     */
    public StageAgent deterministic(AgentRole role) {
        StageAgent agent = deterministicAgents.get(role);
        if (agent == null) {
            throw new IllegalArgumentException("No deterministic agent registered for role " + role);
        }
        return agent;
    }

    /**
     * Returns whether a deterministic agent exists for the role.
     *
     * @param role role
     * @return true when registered
     */
    public boolean hasDeterministic(AgentRole role) {
        return deterministicAgents.containsKey(role);
    }

    /**
     * Returns whether a provider id is known ({@code deterministic} always is).
     *
     * @param providerId provider id or null
     * @return true when known
     */
    public boolean hasProvider(@Nullable String providerId) {
        return providerId == null || providerId.isBlank() || DETERMINISTIC.equals(providerId)
                || providers.containsKey(providerId);
    }

    /**
     * Returns all provider ids, {@code deterministic} first.
     *
     * @return provider ids
     */
    public Set<String> providerIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(DETERMINISTIC);
        ids.addAll(providers.keySet());
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Builder of {@link AgentRegistry}.
     */
    public static final class Builder {

        private final Map<AgentRole, StageAgent> deterministicAgents = new EnumMap<>(AgentRole.class);
        private final Map<String, Map<AgentRole, StageAgent>> providers = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Registers deterministic agents (one per role; later registrations replace earlier ones).
         *
         * @param agents agents
         * @return this builder
         */
        public Builder deterministic(Collection<? extends StageAgent> agents) {
            agents.forEach(a -> deterministicAgents.put(a.role(), a));
            return this;
        }

        /**
         * Registers deterministic agents.
         *
         * @param agents agents
         * @return this builder
         */
        public Builder deterministic(StageAgent... agents) {
            return deterministic(List.of(agents));
        }

        /**
         * Registers the agents of a provider.
         *
         * @param providerId provider id (not {@code deterministic})
         * @param agents agents (one per role)
         * @return this builder
         */
        public Builder provider(String providerId, Collection<? extends StageAgent> agents) {
            Objects.requireNonNull(providerId, "providerId");
            if (DETERMINISTIC.equals(providerId)) {
                throw new IllegalArgumentException("use deterministic(...) for the deterministic provider");
            }
            Map<AgentRole, StageAgent> byRole = providers.computeIfAbsent(providerId,
                    k -> new EnumMap<>(AgentRole.class));
            agents.forEach(a -> byRole.put(a.role(), a));
            return this;
        }

        /**
         * Builds the registry.
         *
         * @return the registry
         */
        public AgentRegistry build() {
            return new AgentRegistry(deterministicAgents, providers);
        }
    }
}
