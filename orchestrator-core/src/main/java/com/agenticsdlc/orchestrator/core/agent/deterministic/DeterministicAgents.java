package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.workspace.BuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;
import com.agenticsdlc.orchestrator.core.workspace.ProcessBuildRunner;

/**
 * Factory of the ten deterministic agents (one per role).
 */
public final class DeterministicAgents {

    private DeterministicAgents() {
    }

    /**
     * Returns all deterministic agents with a {@link ProcessBuildRunner} for optional sandbox builds.
     *
     * @return one agent per role
     */
    public static List<StageAgent> all() {
        return all(new ProcessBuildRunner());
    }

    /**
     * Returns all deterministic agents.
     *
     * @param buildRunner runner for optional sandbox builds, or null for structural verification only
     * @return one agent per role
     */
    public static List<StageAgent> all(@Nullable BuildRunner buildRunner) {
        return List.of(new RequirementsAgent(), new PlanningAgent(), new CodebaseReasoningAgent(new ImpactAnalyzer()),
                new ArchitectureAgent(), new ImplementationAgent(), new TestDesignAgent(),
                new VerificationAgent(buildRunner), new DocumentationAgent(), new SecurityReviewAgent(),
                new ReleaseAgent());
    }
}
