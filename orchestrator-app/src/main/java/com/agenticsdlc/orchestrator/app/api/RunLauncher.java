package com.agenticsdlc.orchestrator.app.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.api.dto.CreateRunRequest;
import com.agenticsdlc.orchestrator.app.llm.ProviderCatalog;
import com.agenticsdlc.orchestrator.app.scenario.ScenarioDefinition;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunBudget;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * Turns run requests (custom or from the scenario catalog) into engine starts: resolves and checks the agent
 * provider (an unavailable provider is refused instead of starting a run that would only fall back), applies the
 * defaults of the API contract and records the starting operator as the run tag {@value #CREATED_BY_TAG}.
 */
@Component
public class RunLauncher {

    /** Run tag holding the operator who started the run. */
    public static final String CREATED_BY_TAG = "createdBy";

    private final OrchestrationEngine engine;
    private final ProviderCatalog providers;

    /**
     * Creates the launcher.
     *
     * @param engine the engine
     * @param providers the provider catalog
     */
    public RunLauncher(OrchestrationEngine engine, ProviderCatalog providers) {
        this.engine = engine;
        this.providers = providers;
    }

    /**
     * Starts a run from an API request.
     *
     * @param request the validated request
     * @param operator the operator starting the run
     * @return a snapshot of the new run
     */
    public Run launch(CreateRunRequest request, Actor operator) {
        List<FaultSpec> faults = request.faults() == null ? List.of() : request.faults().stream()
                .map(f -> new FaultSpec(f.stageId().strip(), f.failTimes(), f.kind())).toList();
        String scenario = request.scenario() == null || request.scenario().isBlank() ? "custom" : request.scenario();
        return start(request.requirement(), scenario,
                Objects.requireNonNullElse(request.autonomy(), AutonomyLevel.GUIDED), request.provider(), faults,
                Objects.requireNonNullElse(request.workspace(), WorkspaceMode.REPO), budget(request.budget()),
                operator);
    }

    /**
     * Starts a run of a catalog scenario.
     *
     * @param scenario the scenario
     * @param provider requested provider, or null for the default
     * @param operator the operator starting the run
     * @return a snapshot of the new run
     */
    public Run launch(ScenarioDefinition scenario, @Nullable String provider, Actor operator) {
        return start(scenario.requirement(), scenario.key(), scenario.autonomy(), provider, scenario.faults(),
                scenario.workspace(), null, operator);
    }

    private Run start(String requirement, String scenario, AutonomyLevel autonomy, @Nullable String provider,
                      List<FaultSpec> faults, WorkspaceMode workspace, @Nullable RunBudget budget, Actor operator) {
        String providerId = providers.resolveAvailable(provider);
        RunOptions options = new RunOptions(autonomy, providerId, faults, budget, workspace,
                Map.of(CREATED_BY_TAG, operator.id()));
        return engine.start(requirement, scenario, options, operator);
    }

    private static @Nullable RunBudget budget(CreateRunRequest.@Nullable BudgetRequest request) {
        if (request == null) {
            return null;
        }
        RunBudget budget = RunBudget.DEFAULT;
        if (request.maxFeedbackLoops() != null) {
            budget = budget.withMaxFeedbackLoops(request.maxFeedbackLoops());
        }
        if (request.maxReplans() != null) {
            budget = budget.withMaxReplans(request.maxReplans());
        }
        if (request.maxAgentCalls() != null) {
            budget = budget.withMaxAgentCalls(request.maxAgentCalls());
        }
        if (request.maxLlmTokens() != null) {
            budget = budget.withMaxLlmTokens(request.maxLlmTokens());
        }
        return budget;
    }
}
