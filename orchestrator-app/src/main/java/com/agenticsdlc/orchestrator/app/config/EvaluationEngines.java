package com.agenticsdlc.orchestrator.app.config;

import java.time.Clock;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.audit.UnsignedChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuite.EvaluationCase;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuiteRunner;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Builds the engines of evaluation suite runs: for every case a fresh, isolated {@link OrchestrationEngine} with the
 * deterministic agents only, an in-memory run store, audit sink and lease provider, no metrics, no sandbox and
 * immediate retries. Nothing it does reaches the production store, audit trail, metrics or leases: the production
 * engine is never used. It shares the read-only parts of the control plane: the workflow graph, the policy engine (so
 * a configured policy pack is evaluated too), the clock and the current workspace index (REPO cases reason over it).
 */
public final class EvaluationEngines implements EvaluationSuiteRunner.EngineFactory {

    private final WorkflowGraph graph;
    private final PolicyEngine policyEngine;
    private final WorkspaceIndexHolder workspace;
    private final Clock clock;

    /**
     * Creates the factory.
     *
     * @param graph the workflow graph
     * @param policyEngine the policy engine of the control plane (stateless, shared)
     * @param workspace the workspace index holder (read only)
     * @param clock the clock
     */
    public EvaluationEngines(WorkflowGraph graph, PolicyEngine policyEngine, WorkspaceIndexHolder workspace,
                             Clock clock) {
        this.graph = graph;
        this.policyEngine = policyEngine;
        this.workspace = workspace;
        this.clock = clock;
    }

    @Override
    public OrchestrationEngine create(EvaluationCase evaluationCase) {
        EngineConfig config = EngineConfig.builder()
                .backoffScale(0.0)
                .clock(clock)
                .sandboxEnabled(false)
                .verifyBuildEnabled(false)
                .workspaceRoot(workspace.root())
                .workspaceIndex(workspace::current)
                .changeRecordSigner(UnsignedChangeRecordSigner.INSTANCE)
                .leaseProvider(new InMemoryRunLeaseProvider(clock))
                .nodeId("evaluation-" + UUID.randomUUID())
                .build();
        return new OrchestrationEngine(graph, AgentRegistry.builder().deterministic(DeterministicAgents.all()).build(),
                policyEngine, new InMemoryRunStore(), new InMemoryAuditSink(), NoopMetricsRecorder.INSTANCE, config);
    }

    @Override
    public @Nullable CodebaseIndex index(EvaluationCase evaluationCase) {
        return evaluationCase.workspace() == WorkspaceMode.REPO ? workspace.current() : null;
    }
}
