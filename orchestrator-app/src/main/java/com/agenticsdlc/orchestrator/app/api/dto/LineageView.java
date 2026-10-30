package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

/**
 * Artifact lineage of a run: every artifact version and the inputs it was derived from.
 *
 * @param nodes artifact versions
 * @param edges edges from an upstream artifact to the artifact derived from it (artifact ids)
 */
public record LineageView(List<LineageNodeView> nodes, List<LineageEdgeView> edges) {

    /**
     * An artifact version.
     *
     * @param artifactId artifact id
     * @param stageId producing stage
     * @param kind artifact kind
     * @param version version
     * @param hash payload hash
     * @param accepted whether it is the accepted version of the stage
     */
    public record LineageNodeView(String artifactId, String stageId, String kind, int version, String hash,
                                  boolean accepted) {
    }

    /**
     * A derivation edge.
     *
     * @param from upstream artifact id
     * @param to derived artifact id
     */
    public record LineageEdgeView(String from, String to) {
    }
}
