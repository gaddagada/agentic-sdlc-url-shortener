package com.agenticsdlc.orchestrator.core.agent.deterministic;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

/**
 * Deterministic codebase analyst ({@code impact}): runs the {@link ImpactAnalyzer} over the workspace index
 * for REPO runs, or proposes a greenfield structure when there is no codebase.
 */
public final class CodebaseReasoningAgent implements StageAgent {

    private final ImpactAnalyzer analyzer;

    /**
     * Creates the agent with a default analyzer.
     */
    public CodebaseReasoningAgent() {
        this(new ImpactAnalyzer());
    }

    /**
     * Creates the agent.
     *
     * @param analyzer impact analyzer
     */
    public CodebaseReasoningAgent(ImpactAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public AgentRole role() {
        return AgentRole.CODEBASE_ANALYSIS;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        RequirementSpec spec = context.require(RequirementSpec.class);
        CodebaseIndex index = context.workspaceIndex();
        boolean repo = context.options().workspace() == WorkspaceMode.REPO;
        ImpactAnalysis analysis = repo && index != null && !index.isEmpty() ? analyzer.analyze(index, spec)
                : analyzer.proposeGreenfield(spec);
        String rationale = repo && (index == null || index.isEmpty())
                ? "workspace mode REPO but no codebase index is available; greenfield structure proposed"
                : analysis.summary();
        return StageResult.success(analysis, rationale);
    }
}
