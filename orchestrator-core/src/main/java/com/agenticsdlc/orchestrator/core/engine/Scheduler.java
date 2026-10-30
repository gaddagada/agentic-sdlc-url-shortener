package com.agenticsdlc.orchestrator.core.engine;

import java.util.ArrayList;
import java.util.List;

import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * Computes which stages are ready and the run's waiting state. Pure functions over the run.
 */
final class Scheduler {

    private final WorkflowGraph graph;

    Scheduler(WorkflowGraph graph) {
        this.graph = graph;
    }

    /**
     * Returns the stages that can be scheduled: status PENDING or STALE and every predecessor SUCCEEDED,
     * in topological order. The caller checks that the run is RUNNING or WAITING_HUMAN.
     */
    List<StageDefinition> readyStages(Run run) {
        List<StageDefinition> ready = new ArrayList<>();
        for (String stageId : graph.topologicalOrder()) {
            StageExecution exec = run.stage(stageId);
            if (!exec.status().isSchedulable()) {
                continue;
            }
            boolean depsDone = graph.predecessors(stageId).stream()
                    .allMatch(dep -> run.stage(dep).status() == StageStatus.SUCCEEDED);
            if (depsDone) {
                ready.add(graph.stage(stageId));
            }
        }
        return ready;
    }

    boolean allSucceeded(Run run) {
        return run.stages().stream().allMatch(s -> s.status() == StageStatus.SUCCEEDED);
    }

    boolean anyInProgress(Run run) {
        return run.stages().stream().anyMatch(s -> s.status().isInProgress());
    }

    boolean anyAwaitingHuman(Run run) {
        return run.stages().stream().anyMatch(s -> s.status().isAwaitingHuman());
    }
}
