package com.agenticsdlc.orchestrator.app.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.api.dto.ApprovalView;
import com.agenticsdlc.orchestrator.app.api.dto.ClarificationQuestionView;
import com.agenticsdlc.orchestrator.app.api.dto.GraphView;
import com.agenticsdlc.orchestrator.app.api.dto.PendingActionView;
import com.agenticsdlc.orchestrator.app.api.dto.PolicyFindingView;
import com.agenticsdlc.orchestrator.app.api.dto.RunSummaryView;
import com.agenticsdlc.orchestrator.app.api.dto.RunView;
import com.agenticsdlc.orchestrator.app.api.dto.StageView;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.Clarification;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunBudget;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * Maps engine run snapshots to the run, run summary and graph views of the REST API. Stateless and thread-safe.
 */
@Component
public class RunViewMapper {

    /** Maximum length of the requirement in run summaries. */
    static final int SUMMARY_REQUIREMENT_MAX = 120;

    private final WorkflowGraph graph;
    private final Clock clock;

    /**
     * Creates the mapper.
     *
     * @param graph the workflow graph runs are created with
     * @param clock clock used for open human-wait intervals
     */
    public RunViewMapper(WorkflowGraph graph, Clock clock) {
        this.graph = graph;
        this.clock = clock;
    }

    /**
     * Maps a run to its full view.
     *
     * @param run a run snapshot
     * @return the view
     */
    public RunView toView(Run run) {
        Instant now = clock.instant();
        List<StageView> stages = graph.stages().stream().map(def -> stage(run, def)).toList();
        RunBudget budget = run.budget();
        RunCounters c = run.counters();
        RiskAssessment risk = run.currentRisk();
        return new RunView(run.id(), run.scenarioKey(), run.status().name(), name(run.haltReason()),
                run.haltDetail(), run.requirement(), run.activeRequirement(), run.options().autonomy().name(),
                provider(run), run.options().workspace().name(), run.options().tags().get(RunLauncher.CREATED_BY_TAG),
                run.planVersion(), run.createdAt(), run.startedAt(), run.updatedAt(), run.endedAt(), stages,
                pendingActions(run), clarification(run.clarification()),
                new RunView.BudgetView(budget.maxFeedbackLoops(), budget.maxReplans(), budget.maxAgentCalls(),
                        budget.maxLlmTokens(), budget.maxActiveDuration().toSeconds(), c.agentCalls(),
                        c.llmTokens()),
                new RunView.CountersView(c.retries(), c.fallbacks(), c.rollbacks(), c.replans(), c.feedbackLoops(),
                        c.approvalsRequested(), c.approvalsApproved(), c.approvalsRejected(), c.approvalsRevoked(),
                        c.agentCalls(), c.llmCalls(), c.llmInputTokens(), c.llmOutputTokens()),
                new RunView.TimingView(run.endToEndMs(), run.activeMs(now), run.humanWaitMs(now),
                        run.firstFailureAt(), run.recoveredAt(), run.mttrMs()),
                new RunView.RiskView(risk.level().name(), risk.factors()),
                run.policyFindings().stream().map(PolicyFindingView::from).toList());
    }

    /**
     * Maps a run to its summary for the runs list.
     *
     * @param run a run snapshot
     * @return the summary
     */
    public RunSummaryView toSummary(Run run) {
        int succeeded = (int) run.stages().stream().filter(s -> s.status() == StageStatus.SUCCEEDED).count();
        int pending = (int) run.stages().stream().filter(s -> s.status().isAwaitingHuman()).count();
        String requirement = run.requirement().length() <= SUMMARY_REQUIREMENT_MAX ? run.requirement()
                : run.requirement().substring(0, SUMMARY_REQUIREMENT_MAX - 1) + "…";
        return new RunSummaryView(run.id(), run.scenarioKey(), run.status().name(), name(run.haltReason()),
                requirement, run.planVersion(), run.createdAt(), run.updatedAt(), pending,
                new RunSummaryView.ProgressView(succeeded, run.stages().size()));
    }

    /**
     * Maps a run to its pipeline graph.
     *
     * @param run a run snapshot
     * @return the graph view
     */
    public GraphView toGraph(Run run) {
        List<GraphView.GraphNodeView> nodes = new ArrayList<>();
        for (StageDefinition def : graph.stages()) {
            StageExecution exec = run.stage(def.id());
            nodes.add(new GraphView.GraphNodeView(def.id(), def.name(), exec.status().name(),
                    graph.layerOf(def.id()), def.role().name(), exec.activeAgent(), exec.attempts(),
                    approval(run, exec), shownVersion(run, exec), run.acceptedVersions().get(def.id())));
        }
        List<GraphView.GraphEdgeView> edges = graph.edges().stream()
                .map(e -> new GraphView.GraphEdgeView(e.getKey(), e.getValue())).toList();
        return new GraphView(List.copyOf(nodes), edges, graph.layers());
    }

    /**
     * Derives the pending human actions: approvals of stages AWAITING_APPROVAL and the open questions of a stage
     * AWAITING_CLARIFICATION, in workflow order.
     *
     * @param run a run snapshot
     * @return the pending actions
     */
    List<PendingActionView> pendingActions(Run run) {
        List<PendingActionView> actions = new ArrayList<>();
        for (StageDefinition def : graph.stages()) {
            StageExecution exec = run.stage(def.id());
            Approval approval = exec.pendingApproval();
            if (exec.status() == StageStatus.AWAITING_APPROVAL && approval != null) {
                actions.add(new PendingActionView(PendingActionView.APPROVAL, def.id(), approval.kind().name(),
                        approval.id(), approval.reason(), approval.ruleIds(), approval.artifactVersion(),
                        approval.artifactHash(), approval.requestedAt(), null));
            } else if (exec.status() == StageStatus.AWAITING_CLARIFICATION && run.clarification() != null
                    && !run.clarification().answered()) {
                Clarification clarification = run.clarification();
                actions.add(new PendingActionView(PendingActionView.CLARIFICATION, def.id(), null, null, null, null,
                        null, null, clarification.requestedAt(),
                        clarification.questions().stream().map(ClarificationQuestionView::from).toList()));
            }
        }
        return List.copyOf(actions);
    }

    private StageView stage(Run run, StageDefinition def) {
        StageExecution exec = run.stage(def.id());
        return new StageView(def.id(), def.name(), def.role().name(), List.copyOf(def.dependsOn()),
                exec.status().name(), exec.attempts(), exec.activeAgent(), exec.startedAt(), exec.endedAt(),
                exec.durationMs(), exec.lastError(), shownVersion(run, exec), run.acceptedVersions().get(def.id()),
                def.role().artifactKind().name(), approval(run, exec), exec.feedback(), exec.nextAttemptAt());
    }

    /**
     * The artifact version an operator should look at: the version under review while an exit approval is
     * pending (it is accepted only when approved), else the accepted version.
     */
    private static @Nullable Integer shownVersion(Run run, StageExecution exec) {
        Approval pending = exec.pendingApproval();
        if (exec.status() == StageStatus.AWAITING_APPROVAL && pending != null && pending.artifactVersion() != null) {
            return pending.artifactVersion();
        }
        return run.acceptedVersions().get(exec.stageId());
    }

    /** The pending approval of a stage, else its most recent approval (any status), else null. */
    private static @Nullable ApprovalView approval(Run run, StageExecution exec) {
        if (exec.pendingApproval() != null) {
            return ApprovalView.from(exec.pendingApproval());
        }
        Approval latest = null;
        for (Approval approval : run.approvals()) {
            if (approval.stageId().equals(exec.stageId())) {
                latest = approval;
            }
        }
        return latest == null ? null : ApprovalView.from(latest);
    }

    private static RunView.@Nullable ClarificationView clarification(@Nullable Clarification clarification) {
        if (clarification == null) {
            return null;
        }
        return new RunView.ClarificationView(clarification.stageId(),
                clarification.questions().stream().map(ClarificationQuestionView::from).toList(),
                clarification.answers(), clarification.requestedAt(), clarification.answeredAt(),
                clarification.answeredBy());
    }

    private static String provider(Run run) {
        String id = run.options().providerId();
        return id == null || id.isBlank() ? AgentRegistry.DETERMINISTIC : id;
    }

    private static @Nullable String name(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }
}
