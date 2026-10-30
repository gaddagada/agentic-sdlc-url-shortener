package com.agenticsdlc.orchestrator.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;

class WorkflowGraphTest {

    private final WorkflowGraph graph = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());

    private static StageDefinition stage(String id, String... deps) {
        return new StageDefinition(id, id, AgentRole.PLANNING, Set.of(deps), GateSpec.none(), RetryPolicy.of(1),
                Duration.ofSeconds(1), false);
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> new WorkflowGraph(new WorkflowDefinition("w", 1, List.of(stage("a"), stage("a")))))
                .isInstanceOf(InvalidWorkflowException.class).hasMessageContaining("Duplicate stage id: a");
    }

    @Test
    void rejectsUnknownDependencies() {
        assertThatThrownBy(() -> new WorkflowGraph(new WorkflowDefinition("w", 1, List.of(stage("a", "missing")))))
                .isInstanceOf(InvalidWorkflowException.class).hasMessageContaining("unknown stage missing");
    }

    @Test
    void rejectsCycles() {
        assertThatThrownBy(() -> new WorkflowGraph(new WorkflowDefinition("w", 1,
                List.of(stage("a", "c"), stage("b", "a"), stage("c", "b"), stage("d")))))
                .isInstanceOf(InvalidWorkflowException.class).hasMessageContaining("cycle")
                .hasMessageContaining("a").hasMessageContaining("b").hasMessageContaining("c");
        assertThatThrownBy(() -> new WorkflowGraph(new WorkflowDefinition("w", 1, List.of(stage("a", "a")))))
                .isInstanceOf(InvalidWorkflowException.class).hasMessageContaining("itself");
        assertThatThrownBy(() -> new WorkflowGraph(new WorkflowDefinition("w", 1, List.of())))
                .isInstanceOf(InvalidWorkflowException.class);
    }

    @Test
    void defaultGraphHasTheSpecifiedStagesGatesAndRetries() {
        assertThat(graph.definition().id()).isEqualTo("sdlc-default");
        assertThat(graph.definition().version()).isEqualTo(1);
        assertThat(graph.stages()).extracting(StageDefinition::id).containsExactly("intake", "decompose", "impact",
                "design", "implement", "test-design", "verify", "document", "security-scan", "release-readiness");
        assertThat(graph.stage("design").gates().exitApproval()).isEqualTo(ApprovalRequirement.RISK_BASED);
        assertThat(graph.stage("implement").gates().exitApproval()).isEqualTo(ApprovalRequirement.RISK_BASED);
        assertThat(graph.stage("release-readiness").gates().exitApproval()).isEqualTo(ApprovalRequirement.ALWAYS);
        assertThat(graph.stage("verify").retryPolicy().maxAttempts()).isEqualTo(2);
        assertThat(graph.stage("verify").timeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(graph.stage("implement").timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(graph.stage("intake").retryPolicy().maxAttempts()).isEqualTo(3);
        assertThat(graph.predecessors("verify")).containsExactly("implement", "test-design");
        assertThat(graph.predecessors("release-readiness")).containsExactly("verify", "document", "security-scan");
        assertThat(graph.roots()).containsExactly("intake");
    }

    @Test
    void topologicalOrderRespectsDependencies() {
        List<String> order = graph.topologicalOrder();
        for (StageDefinition stage : graph.stages()) {
            for (String dep : stage.dependsOn()) {
                assertThat(order.indexOf(dep)).isLessThan(order.indexOf(stage.id()));
            }
        }
        assertThat(order).containsExactly("intake", "decompose", "impact", "design", "implement", "test-design",
                "verify", "document", "security-scan", "release-readiness");
    }

    @Test
    void layersUseLongestPathAndExposeParallelSegments() {
        assertThat(graph.layers()).containsExactly(
                List.of("intake"),
                List.of("decompose", "impact"),
                List.of("design"),
                List.of("implement", "test-design"),
                List.of("verify", "document", "security-scan"),
                List.of("release-readiness"));
        assertThat(graph.layerOf("release-readiness")).isEqualTo(5);
    }

    @Test
    void downstreamAndUpstreamClosures() {
        assertThat(graph.downstreamOf("design")).containsExactly("implement", "test-design", "verify", "document",
                "security-scan", "release-readiness");
        assertThat(graph.downstreamOf("implement")).containsExactly("verify", "document", "security-scan",
                "release-readiness");
        assertThat(graph.downstreamOf("release-readiness")).isEmpty();
        assertThat(graph.upstreamOf("verify")).containsExactly("intake", "decompose", "impact", "design", "implement",
                "test-design");
        assertThat(graph.successors("intake")).containsExactly("decompose", "impact");
        assertThat(graph.edges()).hasSize(13);
    }

    @Test
    void retryPolicyBackoffIsExponentialCappedAndScaled() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), 2.0);
        Duration cap = Duration.ofSeconds(30);
        assertThat(policy.backoff(1, cap, 1.0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoff(2, cap, 1.0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoff(3, cap, 1.0)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoff(10, cap, 1.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(3, cap, 0.5)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoff(3, cap, 0.0)).isZero();
        assertThatThrownBy(() -> RetryPolicy.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void definitionsCanBeCustomized() {
        WorkflowDefinition changed = WorkflowDefinitions.defaultSdlc()
                .withStage("verify", s -> s.withFallbackToDeterministic(false).withRetryPolicy(RetryPolicy.of(4)));
        assertThat(changed.stages().stream().filter(s -> s.id().equals("verify")).findFirst().orElseThrow())
                .satisfies(s -> {
                    assertThat(s.fallbackToDeterministic()).isFalse();
                    assertThat(s.retryPolicy().maxAttempts()).isEqualTo(4);
                });
        assertThatThrownBy(() -> changed.withStage("nope", s -> s)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stage("Bad Id")).isInstanceOf(IllegalArgumentException.class);
    }
}
