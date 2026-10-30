package com.agenticsdlc.orchestrator.core.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A validated workflow definition with topology queries. Instances are immutable and thread-safe.
 *
 * <p>Validation: at least one stage, unique stage ids, every dependency refers to a known stage, no
 * self-dependency and no cycles. Violations throw {@link InvalidWorkflowException}.</p>
 */
public final class WorkflowGraph {

    private final WorkflowDefinition definition;
    private final Map<String, StageDefinition> stagesById;
    private final Map<String, List<String>> successors;
    private final List<String> topologicalOrder;
    private final Map<String, Integer> layerOf;
    private final Map<String, Integer> topoIndex;

    /**
     * Validates {@code definition} and precomputes its topology.
     *
     * @param definition the workflow definition
     * @throws InvalidWorkflowException when the definition is invalid
     */
    public WorkflowGraph(WorkflowDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (definition.stages().isEmpty()) {
            throw new InvalidWorkflowException("Workflow " + definition.id() + " has no stages");
        }
        Map<String, StageDefinition> byId = new LinkedHashMap<>();
        for (StageDefinition stage : definition.stages()) {
            if (byId.putIfAbsent(stage.id(), stage) != null) {
                throw new InvalidWorkflowException("Duplicate stage id: " + stage.id());
            }
        }
        Map<String, List<String>> succ = new LinkedHashMap<>();
        byId.keySet().forEach(id -> succ.put(id, new ArrayList<>()));
        for (StageDefinition stage : byId.values()) {
            for (String dep : stage.dependsOn()) {
                if (dep.equals(stage.id())) {
                    throw new InvalidWorkflowException("Stage " + stage.id() + " depends on itself");
                }
                if (!byId.containsKey(dep)) {
                    throw new InvalidWorkflowException("Stage " + stage.id() + " depends on unknown stage " + dep);
                }
                succ.get(dep).add(stage.id());
            }
        }
        this.stagesById = Collections.unmodifiableMap(byId);
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        succ.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        this.successors = Collections.unmodifiableMap(frozen);
        this.topologicalOrder = computeTopologicalOrder();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < topologicalOrder.size(); i++) {
            index.put(topologicalOrder.get(i), i);
        }
        this.topoIndex = Collections.unmodifiableMap(index);
        this.layerOf = computeLayers();
    }

    private List<String> computeTopologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        stagesById.values().forEach(s -> indegree.put(s.id(), s.dependsOn().size()));
        List<String> order = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        // Kahn's algorithm with definition order as a stable tie-breaker.
        boolean progressed = true;
        while (order.size() < stagesById.size() && progressed) {
            progressed = false;
            for (StageDefinition stage : stagesById.values()) {
                if (!emitted.contains(stage.id()) && indegree.get(stage.id()) == 0) {
                    order.add(stage.id());
                    emitted.add(stage.id());
                    for (String next : successors.get(stage.id())) {
                        indegree.merge(next, -1, Integer::sum);
                    }
                    progressed = true;
                    break;
                }
            }
        }
        if (order.size() < stagesById.size()) {
            List<String> cyclic = stagesById.keySet().stream().filter(id -> !emitted.contains(id)).toList();
            throw new InvalidWorkflowException("Workflow contains a cycle among stages " + cyclic);
        }
        return List.copyOf(order);
    }

    private Map<String, Integer> computeLayers() {
        Map<String, Integer> layers = new LinkedHashMap<>();
        for (String id : topologicalOrder) {
            int layer = 0;
            for (String dep : stagesById.get(id).dependsOn()) {
                layer = Math.max(layer, layers.get(dep) + 1);
            }
            layers.put(id, layer);
        }
        return Collections.unmodifiableMap(layers);
    }

    /**
     * Returns the underlying definition.
     *
     * @return the definition
     */
    public WorkflowDefinition definition() {
        return definition;
    }

    /**
     * Returns the stages in definition order.
     *
     * @return stages
     */
    public List<StageDefinition> stages() {
        return definition.stages();
    }

    /**
     * Returns the stage with the given id.
     *
     * @param stageId stage id
     * @return the stage
     * @throws IllegalArgumentException when unknown
     */
    public StageDefinition stage(String stageId) {
        StageDefinition stage = stagesById.get(stageId);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage " + stageId);
        }
        return stage;
    }

    /**
     * Returns whether the graph contains the stage.
     *
     * @param stageId stage id
     * @return true when known
     */
    public boolean contains(String stageId) {
        return stagesById.containsKey(stageId);
    }

    /**
     * Returns stage ids in a stable topological order (definition order breaks ties).
     *
     * @return topological order
     */
    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    /**
     * Returns the longest-path layering: layer 0 holds roots, every other stage sits one layer below its
     * deepest predecessor. Stages within a layer keep topological order.
     *
     * @return layers of stage ids
     */
    public List<List<String>> layers() {
        int max = layerOf.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            final int layer = i;
            result.add(topologicalOrder.stream().filter(id -> layerOf.get(id) == layer).toList());
        }
        return List.copyOf(result);
    }

    /**
     * Returns the layer index of a stage.
     *
     * @param stageId stage id
     * @return layer index (0 for roots)
     */
    public int layerOf(String stageId) {
        stage(stageId);
        return layerOf.get(stageId);
    }

    /**
     * Returns the direct predecessors of a stage.
     *
     * @param stageId stage id
     * @return predecessor ids
     */
    public Set<String> predecessors(String stageId) {
        return stage(stageId).dependsOn();
    }

    /**
     * Returns the direct successors of a stage.
     *
     * @param stageId stage id
     * @return successor ids
     */
    public List<String> successors(String stageId) {
        stage(stageId);
        return successors.get(stageId);
    }

    /**
     * Returns the transitive successors of a stage (excluding the stage), in topological order.
     *
     * @param stageId stage id
     * @return downstream stage ids
     */
    public Set<String> downstreamOf(String stageId) {
        stage(stageId);
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(successors.get(stageId));
        while (!stack.isEmpty()) {
            String id = stack.removeLast();
            if (seen.add(id)) {
                stack.addAll(successors.get(id));
            }
        }
        return inTopologicalOrder(seen);
    }

    /**
     * Returns the transitive predecessors of a stage (excluding the stage), in topological order.
     *
     * @param stageId stage id
     * @return upstream stage ids
     */
    public Set<String> upstreamOf(String stageId) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(stage(stageId).dependsOn());
        while (!stack.isEmpty()) {
            String id = stack.removeLast();
            if (seen.add(id)) {
                stack.addAll(stagesById.get(id).dependsOn());
            }
        }
        return inTopologicalOrder(seen);
    }

    /**
     * Returns the stages without predecessors.
     *
     * @return root stage ids
     */
    public List<String> roots() {
        return topologicalOrder.stream().filter(id -> stagesById.get(id).dependsOn().isEmpty()).toList();
    }

    /**
     * Returns the edges as (from, to) pairs in topological order of the target.
     *
     * @return edges
     */
    public List<Map.Entry<String, String>> edges() {
        List<Map.Entry<String, String>> edges = new ArrayList<>();
        for (String id : topologicalOrder) {
            for (String dep : stagesById.get(id).dependsOn()) {
                edges.add(Map.entry(dep, id));
            }
        }
        return List.copyOf(edges);
    }

    private Set<String> inTopologicalOrder(Set<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort((a, b) -> Integer.compare(topoIndex.get(a), topoIndex.get(b)));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
