package com.agenticsdlc.orchestrator.core.artifact;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code decompose} stage: a dependency-ordered task plan.
 *
 * @param tasks tasks ({@code T1..})
 * @param criticalPath task ids on the longest (estimate-weighted) path
 * @param parallelGroups task ids per topological layer; tasks in a group can run in parallel
 * @param sequencingRationale explanation of the ordering
 */
public record TaskPlan(List<Task> tasks, List<String> criticalPath, List<List<String>> parallelGroups,
                       String sequencingRationale) implements ArtifactPayload {

    /**
     * Normalizes collections.
     */
    public TaskPlan {
        tasks = Immutables.list(tasks);
        criticalPath = Immutables.list(criticalPath);
        parallelGroups = parallelGroups == null ? List.of()
                : parallelGroups.stream().map(Immutables::list).toList();
        sequencingRationale = Immutables.text(sequencingRationale);
    }

    /** Task type. */
    public enum TaskType {
        /** Analysis of the existing system. */
        ANALYSIS,
        /** Design work. */
        DESIGN,
        /** Implementation. */
        IMPLEMENT,
        /** Testing. */
        TEST,
        /** Documentation. */
        DOCS,
        /** Operations and observability. */
        OPS,
        /** Governance, review and approval. */
        GOVERNANCE
    }

    /** T-shirt size estimate. */
    public enum Estimate {
        /** Small (weight 1). */
        S(1),
        /** Medium (weight 2). */
        M(2),
        /** Large (weight 3). */
        L(3);

        private final int weight;

        Estimate(int weight) {
            this.weight = weight;
        }

        /**
         * Returns the weight used for critical path computation.
         *
         * @return 1, 2 or 3
         */
        public int weight() {
            return weight;
        }
    }

    /** Who owns a task. */
    public enum Owner {
        /** An agent. */
        AGENT,
        /** A human. */
        HUMAN
    }

    /**
     * A task.
     *
     * @param id {@code Tn}
     * @param title short title
     * @param description description
     * @param type task type
     * @param dependsOn ids of tasks that must finish first
     * @param estimate size estimate
     * @param owner owner
     * @param traces requirement ids
     * @param acceptance acceptance statement
     */
    public record Task(String id, String title, String description, TaskType type, List<String> dependsOn,
                       Estimate estimate, Owner owner, List<String> traces, String acceptance) {
        /**
         * Normalizes fields.
         */
        public Task {
            id = Immutables.text(id);
            title = Immutables.text(title);
            description = Immutables.text(description);
            dependsOn = Immutables.list(dependsOn);
            traces = Immutables.list(traces);
            acceptance = Immutables.text(acceptance);
        }
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.TASK_PLAN;
    }

    @Override
    public void validate() {
        Problems p = new Problems("TaskPlan");
        p.requireNonEmpty(tasks, "tasks");
        p.requireUnique(tasks, Task::id, "tasks");
        Set<String> ids = new HashSet<>();
        tasks.forEach(t -> ids.add(t.id()));
        for (Task t : tasks) {
            p.requirePattern(t.id(), "T\\d+", "task id");
            p.requireText(t.title(), "task " + t.id() + " title");
            p.requireNonNull(t.type(), "task " + t.id() + " type");
            p.requireNonNull(t.estimate(), "task " + t.id() + " estimate");
            p.requireNonNull(t.owner(), "task " + t.id() + " owner");
            for (String dep : t.dependsOn()) {
                p.require(ids.contains(dep), "task " + t.id() + " depends on unknown task " + dep);
                p.require(!dep.equals(t.id()), "task " + t.id() + " depends on itself");
            }
        }
        p.require(isAcyclic(), "task dependencies must be acyclic");
        for (String id : criticalPath) {
            p.require(ids.contains(id), "criticalPath references unknown task " + id);
        }
        for (List<String> group : parallelGroups) {
            for (String id : group) {
                p.require(ids.contains(id), "parallelGroups references unknown task " + id);
            }
        }
        p.throwIfAny();
    }

    private boolean isAcyclic() {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (Task t : tasks) {
            indegree.putIfAbsent(t.id(), 0);
            for (String dep : t.dependsOn()) {
                successors.computeIfAbsent(dep, k -> new java.util.ArrayList<>()).add(t.id());
                indegree.merge(t.id(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            for (String next : successors.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return visited == indegree.size();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Task plan");
        md.table(List.of("ID", "Type", "Title", "Depends on", "Estimate", "Owner", "Traces"),
                tasks.stream().map(t -> List.of(t.id(), String.valueOf(t.type()), t.title(),
                        Markdown.join(t.dependsOn()), String.valueOf(t.estimate()), String.valueOf(t.owner()),
                        Markdown.join(t.traces()))).toList());
        md.field("Critical path", String.join(" -> ", criticalPath));
        md.heading(3, "Parallel groups");
        md.bullets(parallelGroups.stream().map(g -> String.join(", ", g)).toList());
        md.heading(3, "Sequencing rationale").paragraph(sequencingRationale);
        return md.render();
    }
}
