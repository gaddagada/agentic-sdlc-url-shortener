package com.agenticsdlc.orchestrator.app.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;

/**
 * Provides the {@link EvaluationEngines} factory of evaluation suite runs (isolated in-memory engines with the
 * deterministic agents). Whether the suite endpoint may use it is decided by {@code sdlc.evaluation.enabled}.
 */
@Configuration(proxyBeanMethods = false)
public class EvaluationConfiguration {

    /**
     * Returns the factory of isolated evaluation engines.
     *
     * @param graph workflow graph
     * @param policyEngine policy engine
     * @param workspace workspace index holder
     * @param clock clock
     * @return the factory
     */
    @Bean
    EvaluationEngines evaluationEngines(WorkflowGraph graph, PolicyEngine policyEngine,
                                        WorkspaceIndexHolder workspace, Clock clock) {
        return new EvaluationEngines(graph, policyEngine, workspace, clock);
    }
}
