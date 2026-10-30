package com.agenticsdlc.orchestrator.core.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.util.Immutables;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Immutable view of everything an agent may use for one attempt. Built by the engine under the run lock.
 *
 * @param runId run id
 * @param stageId stage id
 * @param role agent role
 * @param requirement active requirement
 * @param originalRequirement original requirement
 * @param clarificationAnswers answers by question id (empty when none)
 * @param clarificationQuestions the questions that were answered (empty when none)
 * @param upstream accepted artifacts of all transitive predecessors, by stage id, in topological order
 * @param feedback reviewer comments and verification feedback for this stage
 * @param attempt attempt number within the activation (1-based)
 * @param planVersion plan version
 * @param previousError error of the previous attempt (fed back to LLM agents), or null
 * @param workspaceIndex codebase index when the run uses the repository workspace, else null
 * @param options run options
 * @param approvals approvals granted in the current plan version (read-only evidence)
 * @param settings engine settings for agents
 * @param directPredecessors ids of the stage's direct predecessors in the workflow graph (its {@code dependsOn});
 *                           LLM prompts embed the full JSON only of these artifacts (and of the requirement
 *                           specification) and a compact summary of the other upstream artifacts
 */
public record StageContext(
        String runId,
        String stageId,
        AgentRole role,
        String requirement,
        String originalRequirement,
        Map<String, String> clarificationAnswers,
        List<ClarificationQuestion> clarificationQuestions,
        Map<String, Artifact> upstream,
        List<String> feedback,
        int attempt,
        int planVersion,
        @Nullable String previousError,
        @Nullable CodebaseIndex workspaceIndex,
        RunOptions options,
        List<Approval> approvals,
        AgentSettings settings,
        Set<String> directPredecessors) {

    /**
     * Copies collections.
     */
    public StageContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(settings, "settings");
        requirement = Immutables.text(requirement);
        originalRequirement = Immutables.text(originalRequirement);
        clarificationAnswers = Immutables.map(clarificationAnswers);
        clarificationQuestions = Immutables.list(clarificationQuestions);
        upstream = Immutables.map(upstream);
        feedback = Immutables.list(feedback);
        approvals = Immutables.list(approvals);
        directPredecessors = Immutables.sortedSet(directPredecessors);
    }

    /**
     * Creates a context without explicit graph information: every upstream artifact is treated as a direct
     * predecessor (prompts then embed all upstream artifacts in full, the behaviour before direct predecessors
     * were known).
     *
     * @param runId run id
     * @param stageId stage id
     * @param role agent role
     * @param requirement active requirement
     * @param originalRequirement original requirement
     * @param clarificationAnswers answers by question id
     * @param clarificationQuestions the questions that were answered
     * @param upstream accepted artifacts of all transitive predecessors, by stage id
     * @param feedback feedback for this stage
     * @param attempt attempt number (1-based)
     * @param planVersion plan version
     * @param previousError error of the previous attempt, or null
     * @param workspaceIndex codebase index, or null
     * @param options run options
     * @param approvals approvals granted in the current plan version
     * @param settings engine settings for agents
     */
    public StageContext(String runId, String stageId, AgentRole role, String requirement, String originalRequirement,
                        Map<String, String> clarificationAnswers, List<ClarificationQuestion> clarificationQuestions,
                        Map<String, Artifact> upstream, List<String> feedback, int attempt, int planVersion,
                        @Nullable String previousError, @Nullable CodebaseIndex workspaceIndex, RunOptions options,
                        List<Approval> approvals, AgentSettings settings) {
        this(runId, stageId, role, requirement, originalRequirement, clarificationAnswers, clarificationQuestions,
                upstream, feedback, attempt, planVersion, previousError, workspaceIndex, options, approvals, settings,
                upstream == null ? Set.of() : upstream.keySet());
    }

    /**
     * Returns whether an upstream stage is a direct predecessor of this stage.
     *
     * @param upstreamStageId upstream stage id
     * @return true for direct predecessors
     */
    public boolean isDirectPredecessor(String upstreamStageId) {
        return directPredecessors.contains(upstreamStageId);
    }

    /**
     * Returns the payload of the first upstream artifact of the given type.
     *
     * @param type payload type
     * @param <T> payload type
     * @return the payload
     */
    public <T extends ArtifactPayload> Optional<T> find(Class<T> type) {
        return upstream.values().stream().map(Artifact::payload).filter(type::isInstance).map(type::cast).findFirst();
    }

    /**
     * Returns the payload of the first upstream artifact of the given type.
     *
     * @param type payload type
     * @param <T> payload type
     * @return the payload
     * @throws StageFailure (FATAL) when no such upstream artifact exists
     */
    public <T extends ArtifactPayload> T require(Class<T> type) throws StageFailure {
        return find(type).orElseThrow(() -> StageFailure.fatal("stage " + stageId + " requires an upstream "
                + type.getSimpleName() + " artifact"));
    }

    /**
     * Returns the upstream artifact versions exposed to the agent (provenance {@code derivedFrom}).
     *
     * @return stage id to version
     */
    public Map<String, Integer> derivedFrom() {
        Map<String, Integer> result = new LinkedHashMap<>();
        upstream.forEach((stage, artifact) -> result.put(stage, artifact.version()));
        return result;
    }

    /**
     * Returns whether clarification answers are present.
     *
     * @return true when answered
     */
    public boolean hasClarificationAnswers() {
        return !clarificationAnswers.isEmpty();
    }
}
