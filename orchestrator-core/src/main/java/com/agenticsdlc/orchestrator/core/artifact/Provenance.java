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
 * @param inputTokens LLM input tokens (0 for deterministic agents)
 * @param outputTokens LLM output tokens (0 for deterministic agents)
 * @param latencyMs agent call latency
 * @param attempt attempt number within the stage activation that produced the artifact
 * @param planVersion plan version of the run when the artifact was produced
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
        int planVersion) {

    /**
     * Normalizes fields.
     */
    public Provenance {
        derivedFrom = Immutables.map(derivedFrom);
        agentId = Immutables.text(agentId);
        providerId = Immutables.text(providerId);
    }
}
