package com.agenticsdlc.orchestrator.core.artifact;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Where an artifact came from: the decision lineage of the run.
 *
 * @param derivedFrom upstream stage id to the accepted artifact version the agent was given
 * @param agentRole role of the producing agent
 * @param agentId producing agent id ({@code deterministic}, {@code fallback:deterministic},
 *                {@code anthropic:<model>}, {@code engine})
 * @param providerId provider id ({@code deterministic}, {@code anthropic}, {@code ollama}, {@code engine})
 * @param model model name for LLM agents, null for deterministic agents
 * @param promptHash SHA-256 of the prompt for LLM agents, null for deterministic agents
 * @param inputTokens LLM input tokens, prompt cache reads and writes included (0 for deterministic agents)
 * @param outputTokens LLM output tokens (0 for deterministic agents)
 * @param latencyMs agent call latency
 * @param attempt attempt number within the stage activation that produced the artifact
 * @param planVersion plan version of the run when the artifact was produced
 * @param cacheReadTokens LLM input tokens served from the provider's prompt cache (0 for deterministic agents and
 *                        for artifacts recorded before the field existed)
 * @param cacheCreationTokens LLM input tokens written to the provider's prompt cache (0 for deterministic agents and
 *                            for artifacts recorded before the field existed; boxed like {@code cacheReadTokens} so
 *                            that JSON written before the fields existed still reads, never null after construction)
 * @param costMicroUsd estimated cost of the LLM call that produced the artifact in micro-dollars (millionths of a
 *                     US dollar) from the engine's price book; 0 for deterministic agents; null when the call could not
 *                     be priced (no price configured for the provider and model) or was recorded before costs existed
 */
public record Provenance(
        Map<String, Integer> derivedFrom,
        AgentRole agentRole,
        String agentId,
        String providerId,
        @Nullable String model,
        @Nullable String promptHash,
        long inputTokens,
        long outputTokens,
        long latencyMs,
        int attempt,
        int planVersion,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        @Nullable Long costMicroUsd) {

    /**
     * Normalizes fields; absent cache counts become 0.
     */
    @SuppressWarnings("ConstantValue")
    public Provenance {
        cacheReadTokens = cacheReadTokens == null ? 0L : cacheReadTokens;
        cacheCreationTokens = cacheCreationTokens == null ? 0L : cacheCreationTokens;
        derivedFrom = Immutables.map(derivedFrom);
        agentId = Immutables.text(agentId);
        providerId = Immutables.text(providerId);
    }

    /**
     * Creates provenance without prompt cache counts and cost (the canonical constructor before they existed): cache
     * counts 0; the cost is 0 for provenance without a model and without tokens (deterministic agents, the engine)
     * and null (unknown) otherwise.
     *
     * @param derivedFrom upstream stage id to accepted artifact version
     * @param agentRole role of the producing agent
     * @param agentId producing agent id
     * @param providerId provider id
     * @param model model name, or null
     * @param promptHash prompt hash, or null
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     * @param latencyMs latency
     * @param attempt attempt number
     * @param planVersion plan version
     */
    public Provenance(Map<String, Integer> derivedFrom, AgentRole agentRole, String agentId, String providerId,
                      @Nullable String model, @Nullable String promptHash, long inputTokens, long outputTokens,
                      long latencyMs, int attempt, int planVersion) {
        this(derivedFrom, agentRole, agentId, providerId, model, promptHash, inputTokens, outputTokens, latencyMs,
                attempt, planVersion, 0L, 0L, inputTokens == 0L && outputTokens == 0L && model == null ? 0L : null);
    }
}
