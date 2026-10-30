package com.agenticsdlc.orchestrator.core.graph;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A versioned workflow definition: an ordered list of stages. Structural validation happens in
 * {@link WorkflowGraph}.
 *
 * @param id workflow id, for example {@code sdlc-default}
 * @param version workflow version
 * @param stages stages in definition order
 */
public record WorkflowDefinition(String id, int version, List<StageDefinition> stages) {

    /**
     * Copies the stage list.
     */
    public WorkflowDefinition {
        Objects.requireNonNull(id, "id");
        stages = List.copyOf(stages);
    }

    /**
     * Returns a copy in which stage {@code stageId} is replaced by {@code change.apply(stage)}.
     *
     * @param stageId the stage to change
     * @param change the change
     * @return the modified definition
     * @throws IllegalArgumentException when the stage does not exist
     */
    public WorkflowDefinition withStage(String stageId, UnaryOperator<StageDefinition> change) {
        if (stages.stream().noneMatch(s -> s.id().equals(stageId))) {
            throw new IllegalArgumentException("Unknown stage " + stageId);
        }
        return new WorkflowDefinition(id, version,
                stages.stream().map(s -> s.id().equals(stageId) ? change.apply(s) : s).toList());
    }

    /**
     * Returns a copy in which every stage is transformed.
     *
     * @param change the change
     * @return the modified definition
     */
    public WorkflowDefinition withAllStages(UnaryOperator<StageDefinition> change) {
        return new WorkflowDefinition(id, version, stages.stream().map(change).toList());
    }
}
