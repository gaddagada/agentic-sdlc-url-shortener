package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Provenance;

/**
 * Where an artifact came from (decision lineage).
 *
 * @param derivedFrom upstream stage id to the artifact version the producing agent saw
 * @param agentRole agent role
 * @param agentId agent id
 * @param providerId provider id
 * @param model model, or null
 * @param promptHash SHA-256 of the prompt (LLM agents), or null
 * @param inputTokens input tokens
 * @param outputTokens output tokens
 * @param latencyMs agent latency
 * @param attempt attempt number
 * @param planVersion plan version
 */
public record ProvenanceView(Map<String, Integer> derivedFrom, String agentRole, String agentId, String providerId,
                             @Nullable String model, @Nullable String promptHash, long inputTokens,
                             long outputTokens, long latencyMs, int attempt, int planVersion) {

    /**
     * Maps a core provenance.
     *
     * @param provenance the provenance
     * @return the view
     */
    public static ProvenanceView from(Provenance provenance) {
        return new ProvenanceView(provenance.derivedFrom(), provenance.agentRole().name(), provenance.agentId(),
                provenance.providerId(), provenance.model(), provenance.promptHash(), provenance.inputTokens(),
                provenance.outputTokens(), provenance.latencyMs(), provenance.attempt(), provenance.planVersion());
    }
}
