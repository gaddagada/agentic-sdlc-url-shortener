package com.agenticsdlc.orchestrator.core.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * Dynamic re-planning: invalidation of a stage's downstream closure and requirement changes.
 *
 * <p>Invalidation rules per stage: SUCCEEDED, AWAITING_APPROVAL, AWAITING_CLARIFICATION, RETRY_SCHEDULED and
 * FAILED become STALE (when an accepted artifact exists) or PENDING; RUNNING attempts are cancelled first;
 * PENDING, STALE and CANCELLED stages are unchanged. Pending and granted approvals of invalidated stages
 * are revoked. Artifact history is always kept.</p>
 */
final class ReplanService {

    private final WorkflowGraph graph;
    private final RunControl control;

    ReplanService(WorkflowGraph graph, RunControl control) {
        this.graph = graph;
        this.control = control;
    }

    /**
     * Invalidates the transitive successors of {@code fromStage} (and the stage itself when requested).
     *
     * @return ids of stages whose status changed
     */
    List<String> invalidateDownstream(RunMutation tx, String fromStage, boolean includeSelf, String reason,
                                      Actor actor) {
        Set<String> targets = new LinkedHashSet<>();
        if (includeSelf) {
            targets.add(fromStage);
        }
        targets.addAll(graph.downstreamOf(fromStage));
        return invalidate(tx, targets, reason, actor);
    }

    /**
     * Invalidates the given stages (processed in topological order).
     *
     * @return ids of stages whose status changed
     */
    List<String> invalidate(RunMutation tx, Collection<String> stageIds, String reason, Actor actor) {
        Run run = tx.run;
        List<String> changed = new ArrayList<>();
        for (String stageId : graph.topologicalOrder()) {
            if (!stageIds.contains(stageId)) {
                continue;
            }
            StageExecution exec = run.stage(stageId);
            StageStatus from = exec.status();
            switch (from) {
                case RUNNING -> {
                    control.cancelInFlight(tx, stageId);
                    control.resetToReady(tx, exec, reason, actor);
                }
                case SUCCEEDED, AWAITING_APPROVAL, AWAITING_CLARIFICATION, RETRY_SCHEDULED, FAILED ->
                        control.resetToReady(tx, exec, reason, actor);
                case PENDING, STALE, CANCELLED -> {
                    // nothing to invalidate
                }
            }
            control.revokeApprovals(tx, stageId, reason, actor);
            if (exec.status() != from) {
                changed.add(stageId);
                tx.audit(AuditEventType.STAGE_INVALIDATED, stageId, actor, "stage invalidated: " + reason,
                        RunMutation.data("from", from, "to", exec.status(), "reason", reason));
            }
        }
        return changed;
    }

    /**
     * Applies a requirement change: sets the active requirement, increments the plan version, clears the
     * clarification and stage feedback, invalidates every stage from the roots (artifact history is kept),
     * revokes approvals, cancels in-flight attempts, increments the re-plan counter and audits REPLAN.
     *
     * @return the invalidated stage ids
     */
    List<String> replan(RunMutation tx, String requirement, Actor actor) {
        Run run = tx.run;
        run.setActiveRequirement(requirement);
        int planVersion = run.incrementPlanVersion();
        run.setClarification(null);
        run.stages().forEach(StageExecution::clearFeedback);
        Set<String> targets = new LinkedHashSet<>();
        for (String root : graph.roots()) {
            targets.add(root);
            targets.addAll(graph.downstreamOf(root));
        }
        List<String> invalidated = invalidate(tx, targets, "re-plan to plan v" + planVersion, actor);
        run.updateCounters(RunCounters::plusReplan);
        tx.audit(AuditEventType.REPLAN, null, actor, "re-planned to plan v" + planVersion + "; "
                        + invalidated.size() + " stages invalidated",
                RunMutation.data("planVersion", planVersion, "invalidated", invalidated, "requirementChars",
                        requirement.length(), "nodeId", tx.nodeId));
        return invalidated;
    }
}
