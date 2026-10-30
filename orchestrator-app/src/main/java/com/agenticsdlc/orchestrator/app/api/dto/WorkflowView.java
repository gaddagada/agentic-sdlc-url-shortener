package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;

/**
 * A workflow definition with its layering.
 *
 * @param id workflow id
 * @param version workflow version
 * @param stages stage definitions in topological order
 * @param layers stage ids per layer
 */
public record WorkflowView(String id, int version, List<StageDefinitionView> stages, List<List<String>> layers) {

    /**
     * Maps a workflow graph.
     *
     * @param graph the graph
     * @return the view
     */
    public static WorkflowView from(WorkflowGraph graph) {
        List<StageDefinitionView> stages = graph.topologicalOrder().stream().map(graph::stage)
                .map(StageDefinitionView::from).toList();
        return new WorkflowView(graph.definition().id(), graph.definition().version(), stages, graph.layers());
    }

    /**
     * A stage definition.
     *
     * @param id stage id
     * @param name display name
     * @param role agent role
     * @param dependsOn predecessors
     * @param entryApproval entry gate approval requirement
     * @param exitApproval exit gate approval requirement
     * @param maxAttempts attempts per activation (including the first)
     * @param timeoutSeconds per-attempt timeout
     * @param fallbackToDeterministic whether the deterministic agent takes over when the primary is exhausted
     */
    public record StageDefinitionView(String id, String name, String role, List<String> dependsOn,
                                      String entryApproval, String exitApproval, int maxAttempts,
                                      long timeoutSeconds, boolean fallbackToDeterministic) {

        /**
         * Maps a stage definition.
         *
         * @param stage the definition
         * @return the view
         */
        public static StageDefinitionView from(StageDefinition stage) {
            return new StageDefinitionView(stage.id(), stage.name(), stage.role().name(),
                    List.copyOf(stage.dependsOn()), stage.gates().entryApproval().name(),
                    stage.gates().exitApproval().name(), stage.retryPolicy().maxAttempts(),
                    stage.timeout().toSeconds(), stage.fallbackToDeterministic());
        }
    }
}
