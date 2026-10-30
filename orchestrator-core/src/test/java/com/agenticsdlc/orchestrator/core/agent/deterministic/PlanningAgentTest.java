package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.Estimate;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.Owner;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.Task;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.TaskType;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

class PlanningAgentTest {

    private final PlanningAgent planner = new PlanningAgent();

    private static RequirementSpec spec(String requirement) {
        return new RequirementsAgent().analyze(requirement, List.of(), Map.of(), null, WorkspaceMode.NONE, 0.5);
    }

    @Test
    void greenfieldPlanChainsDesignImplementTestPerRequirementAndEndsWithHumanRelease() {
        RequirementSpec spec = spec("Build a URL shortener service: create short links, redirect with HTTP 302 and "
                + "record click analytics. The p95 redirect latency must be under 50 ms at 500 requests per second. "
                + "The service must prevent redirects to private networks.");
        TaskPlan plan = planner.plan(spec);
        plan.validate();
        int frs = spec.functional().size();
        assertThat(plan.tasks().stream().filter(t -> t.type() == TaskType.DESIGN)).hasSize(frs);
        assertThat(plan.tasks().stream().filter(t -> t.type() == TaskType.IMPLEMENT)).hasSize(frs);
        assertThat(plan.tasks().stream().filter(t -> t.type() == TaskType.ANALYSIS)).isEmpty();
        assertThat(plan.tasks()).anySatisfy(t -> {
            assertThat(t.type()).isEqualTo(TaskType.OPS);
            assertThat(t.traces()).containsExactly("NFR-1");
        });
        assertThat(plan.tasks()).anySatisfy(t -> {
            assertThat(t.type()).isEqualTo(TaskType.GOVERNANCE);
            assertThat(t.owner()).isEqualTo(Owner.HUMAN);
            assertThat(t.title()).startsWith("Review:");
        });
        Task last = plan.tasks().getLast();
        assertThat(last.title()).isEqualTo("Release approval");
        assertThat(last.owner()).isEqualTo(Owner.HUMAN);
        assertThat(plan.tasks().get(plan.tasks().size() - 2).type()).isEqualTo(TaskType.DOCS);
        for (Task task : plan.tasks()) {
            if (task.type() == TaskType.IMPLEMENT) {
                Task design = plan.tasks().stream().filter(t -> t.id().equals(task.dependsOn().getFirst()))
                        .findFirst().orElseThrow();
                assertThat(design.type()).isEqualTo(TaskType.DESIGN);
            }
        }
    }

    @Test
    void brownfieldPlanStartsWithAnalysis() {
        TaskPlan plan = planner.plan(spec("Add a bulk link creation API to the existing URL shortener: accept up to "
                + "100 URLs per request."));
        assertThat(plan.tasks().getFirst().type()).isEqualTo(TaskType.ANALYSIS);
        assertThat(plan.tasks().stream().filter(t -> t.type() == TaskType.DESIGN))
                .allSatisfy(t -> assertThat(t.dependsOn()).containsExactly("T1"));
        assertThat(plan.criticalPath().getFirst()).isEqualTo("T1");
        assertThat(plan.parallelGroups().getFirst()).containsExactly("T1");
    }

    @Test
    void criticalPathIsTheLongestEstimateWeightedPath() {
        List<Task> tasks = List.of(
                task("T1", Estimate.S), task("T2", Estimate.L, "T1"), task("T3", Estimate.S, "T1"),
                task("T4", Estimate.M, "T3"), task("T5", Estimate.S, "T2", "T4"));
        assertThat(PlanningAgent.criticalPath(tasks)).containsExactly("T1", "T2", "T5");
        List<Task> other = List.of(task("T1", Estimate.S), task("T2", Estimate.S, "T1"), task("T3", Estimate.M, "T1"),
                task("T4", Estimate.M, "T3"));
        assertThat(PlanningAgent.criticalPath(other)).containsExactly("T1", "T3", "T4");
    }

    @Test
    void criticalPathWeightIsConsistentWithTheParallelGroups() {
        TaskPlan plan = planner.plan(spec("Build a URL shortener service: create short links and record click "
                + "analytics for every link with 3 metrics."));
        Map<String, Task> byId = new HashMap<>();
        plan.tasks().forEach(t -> byId.put(t.id(), t));
        for (int i = 1; i < plan.criticalPath().size(); i++) {
            assertThat(byId.get(plan.criticalPath().get(i)).dependsOn()).contains(plan.criticalPath().get(i - 1));
        }
        assertThat(plan.parallelGroups().stream().mapToInt(List::size).sum()).isEqualTo(plan.tasks().size());
        assertThat(plan.sequencingRationale()).contains("Critical path weight");
    }

    @Test
    void validationRejectsCyclesAndUnknownDependencies() {
        TaskPlan cyclic = new TaskPlan(List.of(task("T1", Estimate.S, "T2"), task("T2", Estimate.S, "T1")),
                List.of(), List.of(), "x");
        assertThatThrownBy(cyclic::validate).isInstanceOf(InvalidArtifactException.class).hasMessageContaining("acyclic");
        TaskPlan unknown = new TaskPlan(List.of(task("T1", Estimate.S, "T9")), List.of("T1"), List.of(), "x");
        assertThatThrownBy(unknown::validate).isInstanceOf(InvalidArtifactException.class)
                .hasMessageContaining("unknown task T9");
    }

    private static Task task(String id, Estimate estimate, String... deps) {
        return new Task(id, id, id, TaskType.IMPLEMENT, List.of(deps), estimate, Owner.AGENT, List.of(), "done");
    }
}
