package com.agenticsdlc.orchestrator.app.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.RunStore;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * Wires the framework-independent engine: workflow graph, policy engine, JSON codec, workspace index and the
 * {@link OrchestrationEngine} itself (closed on shutdown so in-flight attempts are cancelled cleanly).
 *
 * <p>The {@link JsonCodec} owns its own canonical Jackson 3 mapper (sorted properties, ISO-8601 dates); it is used
 * for persistence snapshots, hashing and LLM output parsing. HTTP bodies use the mapper auto-configured by
 * Spring Boot.</p>
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {

    /**
     * Returns the shared canonical JSON codec.
     *
     * @return the codec
     */
    @Bean
    JsonCodec jsonCodec() {
        return JsonCodec.standard();
    }

    /**
     * Returns the default SDLC workflow graph.
     *
     * @return the graph
     */
    @Bean
    WorkflowGraph workflowGraph() {
        return new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    }

    /**
     * Returns the policy engine with the default rule set.
     *
     * @return the policy engine
     */
    @Bean
    PolicyEngine policyEngine() {
        return PolicyEngine.withDefaultRules();
    }

    /**
     * Returns the workspace index holder (indexed at startup by {@code WorkspaceIndexWarmup}).
     *
     * @param properties configuration
     * @param clock clock
     * @return the holder
     */
    @Bean
    WorkspaceIndexHolder workspaceIndexHolder(OrchestratorProperties properties, Clock clock) {
        return new WorkspaceIndexHolder(properties.workspace().resolvedRoot(), properties.workspace().enabled(),
                new CodebaseIndexer(clock));
    }

    /**
     * Creates the orchestration engine.
     *
     * @param graph workflow graph
     * @param agents agent registry
     * @param policyEngine policy engine
     * @param runStore run store (JPA)
     * @param auditSink audit sink (JPA)
     * @param metrics metrics recorder (Micrometer)
     * @param properties configuration
     * @param workspace workspace index holder
     * @param clock clock
     * @return the engine
     */
    @Bean(destroyMethod = "close")
    OrchestrationEngine orchestrationEngine(WorkflowGraph graph, AgentRegistry agents, PolicyEngine policyEngine,
                                            RunStore runStore, AuditSink auditSink, MetricsRecorder metrics,
                                            OrchestratorProperties properties, WorkspaceIndexHolder workspace,
                                            Clock clock) {
        OrchestratorProperties.Engine engine = properties.engine();
        EngineConfig config = EngineConfig.builder()
                .ambiguityThreshold(engine.ambiguityThreshold())
                .backoffScale(engine.backoffScale())
                .sandboxEnabled(engine.sandboxEnabled())
                .verifyBuildEnabled(engine.verifyBuildEnabled())
                .sandboxRoot(engine.sandboxRoot())
                .llmTimeoutScale(engine.llmTimeoutScale())
                .buildCommand(engine.buildCommand())
                .workspaceRoot(workspace.root())
                .clock(clock)
                .workspaceIndex(workspace::current)
                .build();
        return new OrchestrationEngine(graph, agents, policyEngine, runStore, auditSink, metrics, config);
    }
}
