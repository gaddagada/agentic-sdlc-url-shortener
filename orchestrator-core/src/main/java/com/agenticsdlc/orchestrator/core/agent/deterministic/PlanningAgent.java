package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Requirement;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.Estimate;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.Owner;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.Task;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan.TaskType;

/**
 * Deterministic delivery planner ({@code decompose}): DESIGN, IMPLEMENT and TEST tasks per functional
 * requirement, OPS tasks for performance, reliability and operability NFRs, human GOVERNANCE reviews for
 * security and compliance NFRs, an ANALYSIS task first for brownfield work, a DOCS task and a human release
 * approval last. The task graph is validated as a DAG; the critical path is the longest estimate-weighted
 * path (S=1, M=2, L=3) and parallel groups are the longest-path layers.
 */
public final class PlanningAgent implements StageAgent {

    private static final List<String> LARGE_WORDS = List.of("bulk", "batch", "analytics", "migration", "schema",
            "import", "aggregate*");

    /**
     * Creates the agent.
     */
    public PlanningAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.PLANNING;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        TaskPlan plan = plan(context.require(RequirementSpec.class));
        return StageResult.success(plan, plan.tasks().size() + " tasks; critical path "
                + String.join(" -> ", plan.criticalPath()) + "; " + plan.parallelGroups().size() + " parallel groups");
    }

    /**
     * Builds the task plan.
     *
     * @param spec requirement specification
     * @return the plan
     */
    public TaskPlan plan(RequirementSpec spec) {
        List<Task> tasks = new ArrayList<>();
        List<String> allRequirementIds = spec.allRequirements().stream().map(Requirement::id).toList();
        String analysisId = null;
        if (spec.classification() == RequirementSpec.Classification.BROWNFIELD) {
            analysisId = next(tasks);
            tasks.add(new Task(analysisId, "Review impacted components and regression risks",
                    "Confirm the impact analysis against the codebase and agree on the regression test scope.",
                    TaskType.ANALYSIS, List.of(), Estimate.S, Owner.AGENT, allRequirementIds,
                    "Impacted components and regression tests are confirmed"));
        }
        List<String> implementIds = new ArrayList<>();
        List<String> sinkIds = new ArrayList<>();
        for (Requirement fr : spec.functional()) {
            String title = Text.shorten(fr.text(), 60);
            Estimate size = Text.anyToken(Text.tokens(fr.text()), LARGE_WORDS) || fr.text().length() > 120
                    ? Estimate.L : Estimate.M;
            String design = next(tasks);
            tasks.add(new Task(design, "Design: " + title, "Design components, API and data changes for "
                    + fr.id() + ".", TaskType.DESIGN, analysisId == null ? List.of() : List.of(analysisId),
                    Estimate.S, Owner.AGENT, List.of(fr.id()), "Design for " + fr.id() + " is recorded"));
            String implement = next(tasks);
            tasks.add(new Task(implement, "Implement: " + title, "Implement " + fr.id() + ": " + fr.text(),
                    TaskType.IMPLEMENT, List.of(design), size, Owner.AGENT, List.of(fr.id()),
                    "Code for " + fr.id() + " is complete and reviewed"));
            implementIds.add(implement);
            String test = next(tasks);
            tasks.add(new Task(test, "Test: " + title, "Automate the acceptance criteria of " + fr.id() + ".",
                    TaskType.TEST, List.of(implement), size == Estimate.L ? Estimate.M : Estimate.S, Owner.AGENT,
                    List.of(fr.id()), "Acceptance criteria of " + fr.id() + " pass"));
            sinkIds.add(test);
        }
        List<String> reviewIds = new ArrayList<>();
        for (Requirement nfr : spec.nonFunctional()) {
            String title = Text.shorten(nfr.text(), 60);
            switch (nfr.category()) {
                case PERFORMANCE, RELIABILITY, OPERABILITY -> {
                    String id = next(tasks);
                    String kind = switch (nfr.category()) {
                        case PERFORMANCE -> "Load test and observability";
                        case RELIABILITY -> "Resilience and failure testing";
                        default -> "Health checks and monitoring";
                    };
                    tasks.add(new Task(id, kind + ": " + title, "Verify " + nfr.id() + ": " + nfr.text(),
                            TaskType.OPS, List.copyOf(implementIds), Estimate.M, Owner.AGENT, List.of(nfr.id()),
                            nfr.id() + " is verified with evidence"));
                    sinkIds.add(id);
                }
                case SECURITY, COMPLIANCE -> {
                    String id = next(tasks);
                    tasks.add(new Task(id, "Review: " + title, "Human "
                            + nfr.category().name().toLowerCase(java.util.Locale.ROOT) + " review of " + nfr.id()
                            + ": " + nfr.text(), TaskType.GOVERNANCE,
                            List.copyOf(implementIds), Estimate.S, Owner.HUMAN, List.of(nfr.id()),
                            nfr.id() + " is reviewed and signed off"));
                    reviewIds.add(id);
                }
                case null, default -> {
                    String id = next(tasks);
                    tasks.add(new Task(id, "Validate: " + title, "Validate " + nfr.id() + ": " + nfr.text(),
                            TaskType.TEST, List.copyOf(implementIds), Estimate.S, Owner.AGENT, List.of(nfr.id()),
                            nfr.id() + " is validated"));
                    sinkIds.add(id);
                }
            }
        }
        String docs = next(tasks);
        tasks.add(new Task(docs, "Update documentation", "README section, API reference, runbook and changelog.",
                TaskType.DOCS, List.copyOf(sinkIds.isEmpty() ? implementIds : sinkIds), Estimate.S, Owner.AGENT,
                allRequirementIds, "Documentation reflects the change"));
        List<String> releaseDeps = new ArrayList<>(List.of(docs));
        releaseDeps.addAll(reviewIds);
        String release = next(tasks);
        tasks.add(new Task(release, "Release approval", "Human go/no-go decision based on the release readiness "
                + "report.", TaskType.GOVERNANCE, releaseDeps, Estimate.S, Owner.HUMAN, allRequirementIds,
                "Release is approved by a human operator"));

        Map<String, Integer> layer = layers(tasks);
        List<String> criticalPath = criticalPath(tasks);
        int maxLayer = layer.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<String>> groups = new ArrayList<>();
        for (int i = 0; i <= maxLayer; i++) {
            final int l = i;
            groups.add(tasks.stream().map(Task::id).filter(id -> layer.get(id) == l).toList());
        }
        String rationale = (analysisId != null ? "Impact review first (brownfield). " : "")
                + "Each functional requirement flows design -> implement -> test; requirements are independent, "
                + "so their chains run in parallel. NFR verification and human reviews start once all "
                + "implementation tasks are done; documentation follows testing and the human release approval "
                + "closes the plan. Critical path weight: " + pathWeight(tasks, criticalPath) + " (S=1, M=2, L=3).";
        TaskPlan plan = new TaskPlan(tasks, criticalPath, groups, rationale);
        plan.validate();
        return plan;
    }

    private static String next(List<Task> tasks) {
        return "T" + (tasks.size() + 1);
    }

    private static Map<String, Integer> layers(List<Task> tasks) {
        Map<String, Integer> layer = new LinkedHashMap<>();
        for (Task task : tasks) {
            int l = 0;
            for (String dep : task.dependsOn()) {
                l = Math.max(l, layer.get(dep) + 1);
            }
            layer.put(task.id(), l);
        }
        return layer;
    }

    /** Longest path by estimate weight; tasks are in topological order by construction. */
    static List<String> criticalPath(List<Task> tasks) {
        Map<String, Integer> best = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        String end = null;
        for (Task task : tasks) {
            int base = 0;
            String from = null;
            for (String dep : task.dependsOn()) {
                if (best.get(dep) > base) {
                    base = best.get(dep);
                    from = dep;
                }
            }
            best.put(task.id(), base + task.estimate().weight());
            if (from != null) {
                previous.put(task.id(), from);
            }
            if (end == null || best.get(task.id()) > best.get(end)) {
                end = task.id();
            }
        }
        List<String> path = new ArrayList<>();
        for (String id = end; id != null; id = previous.get(id)) {
            path.addFirst(id);
        }
        return path;
    }

    private static int pathWeight(List<Task> tasks, List<String> path) {
        Map<String, Task> byId = new HashMap<>();
        tasks.forEach(t -> byId.put(t.id(), t));
        return path.stream().mapToInt(id -> byId.get(id).estimate().weight()).sum();
    }
}
