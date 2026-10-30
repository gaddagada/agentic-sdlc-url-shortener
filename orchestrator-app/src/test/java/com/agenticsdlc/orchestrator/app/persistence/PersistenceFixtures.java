package com.agenticsdlc.orchestrator.app.persistence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

/**
 * Beans the JPA adapters need in {@code @DataJpaTest} slices.
 */
@TestConfiguration(proxyBeanMethods = false)
class PersistenceFixtures {

    @Bean
    JsonCodec jsonCodec() {
        return JsonCodec.standard();
    }

    @Bean
    WorkflowGraph workflowGraph() {
        return new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    }
}
