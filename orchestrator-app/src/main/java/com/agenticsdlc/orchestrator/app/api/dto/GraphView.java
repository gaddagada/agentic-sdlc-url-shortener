package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The workflow graph of a run with the live state of every stage, for the pipeline view.
 *
 * @param nodes one node per stage
 * @param edges dependency edges (from predecessor to successor)
 * @param layers stage ids per layer (longest-path layering)
 */
public record GraphView(List<GraphNodeView> nodes, List<GraphEdgeView> edges, List<List<String>> layers) {

    /**
     * A stage node.
     *
     * @param id stage id
     * @param name display name
     * @param status stage status
     * @param layer layer index
     * @param role agent role
     * @param activeAgent agent of the latest attempt
     * @param attempts attempts of the current activation
     * @param approval pending approval, else the latest approval of the stage, or null
     * @param artifactVersion the version under review while an exit approval is pending, else the accepted
     *                        version, or null
     * @param acceptedVersion the accepted artifact version, or null
     */
    public record GraphNodeView(String id, String name, String status, int layer, String role,
                                @Nullable String activeAgent, int attempts, @Nullable ApprovalView approval,
                                @Nullable Integer artifactVersion, @Nullable Integer acceptedVersion) {
    }

    /**
     * A dependency edge.
     *
     * @param from predecessor stage id
     * @param to successor stage id
     */
    public record GraphEdgeView(String from, String to) {
    }
}
