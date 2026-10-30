package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

/**
 * LLM provider agents get the stage timeout multiplied by {@link EngineConfig#llmTimeoutScale()}; deterministic
 * agents keep the plain stage timeout.
 */
class LlmTimeoutScaleTest {

    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;
    private static final String PROVIDER = "fake-llm";
    private static final Duration BASE_TIMEOUT = Duration.ofMillis(200);
    private static final long WORK_MS = 500;

    /** An LLM-provider agent ({@code fake-llm:slow-model}) that needs longer than the base stage timeout. */
    static final class SlowLlmAgent implements StageAgent {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentRole role() {
            return AgentRole.IMPLEMENTATION;
        }

        @Override
        public String id() {
            return PROVIDER + ":slow-model";
        }

        @Override
        public StageResult execute(StageContext context) throws Exception {
            calls.incrementAndGet();
            Thread.sleep(WORK_MS);
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "streamed a long output");
        }
    }

    private final SlowLlmAgent slow = new SlowLlmAgent();
    private final EngineHarness harness = new EngineHarness()
            .definition(d -> d.withStage(IMPLEMENT, s -> s.withTimeout(BASE_TIMEOUT)))
            .provider(PROVIDER, slow);

    @AfterEach
    void close() {
        harness.close();
    }

    private String start() {
        return harness.engine().start("Create short links for HTTP URLs and redirect with HTTP 302.", "timeout-scale",
                RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO).withProvider(PROVIDER)).id();
    }

    private List<AuditEvent> started(String id, String stageId) {
        return harness.events(id).stream().filter(e -> e.type() == AuditEventType.STAGE_STARTED
                && stageId.equals(e.stageId())).toList();
    }

    @Test
    void aSlowLlmAgentSucceedsWithinTheScaledTimeout() {
        // 200 ms x 10 = 2 s for 500 ms of work: a wide margin, so a slow CI machine does not time the agent out
        harness.config(b -> b.llmTimeoutScale(10.0)).start();
        String id = start();

        Run atRelease = harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);
        assertThat(atRelease.stage(IMPLEMENT).attempts()).isEqualTo(1);
        assertThat(slow.calls.get()).isEqualTo(1);
        assertThat(harness.events(id)).noneMatch(e -> e.type() == AuditEventType.RETRY_SCHEDULED
                || e.type() == AuditEventType.FALLBACK_ENGAGED);
        assertThat(started(id, IMPLEMENT)).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("agent", "fake-llm:slow-model");
            assertThat(((Number) e.data().get("timeoutMs")).longValue()).isEqualTo(2_000L);
        });
        assertThat(started(id, WorkflowDefinitions.INTAKE)).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("agent", AgentRegistry.DETERMINISTIC);
            assertThat(((Number) e.data().get("timeoutMs")).longValue()).as("deterministic agents are not scaled")
                    .isEqualTo(Duration.ofMinutes(3).toMillis());
        });
        harness.engine().approve(id, WorkflowDefinitions.RELEASE_READINESS, ALICE, null);
        harness.awaitStatus(id, RunStatus.COMPLETED);
    }

    @Test
    void withoutScalingTheSameAgentTimesOutAndTheDeterministicFallbackTakesOver() {
        harness.config(b -> b.llmTimeoutScale(1.0)).start();
        String id = start();

        harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);
        assertThat(harness.events(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.RETRY_SCHEDULED);
            assertThat(e.stageId()).isEqualTo(IMPLEMENT);
            assertThat(e.data()).containsEntry("kind", "TIMEOUT");
        });
        assertThat(harness.eventTypes(id)).contains(AuditEventType.FALLBACK_ENGAGED);
        assertThat(started(id, IMPLEMENT)).filteredOn(e -> "fake-llm:slow-model".equals(e.data().get("agent")))
                .isNotEmpty().allSatisfy(e -> assertThat(((Number) e.data().get("timeoutMs")).longValue())
                        .isEqualTo(BASE_TIMEOUT.toMillis()));
        assertThat(started(id, IMPLEMENT)).filteredOn(e -> AgentRegistry.FALLBACK_AGENT_ID.equals(e.data()
                .get("agent"))).singleElement().satisfies(e -> assertThat(((Number) e.data().get("timeoutMs"))
                .longValue()).as("the fallback is not scaled").isEqualTo(BASE_TIMEOUT.toMillis()));
    }

    @Test
    void theScaleDefaultsToThreeAndMustBeAtLeastOne() {
        EngineConfig defaults = EngineConfig.defaults();
        assertThat(defaults.llmTimeoutScale()).isEqualTo(3.0).isEqualTo(EngineConfig.DEFAULT_LLM_TIMEOUT_SCALE);
        assertThat(defaults.toBuilder().build().llmTimeoutScale()).isEqualTo(3.0);
        Duration stage = Duration.ofMinutes(3);
        assertThat(defaults.attemptTimeout(stage, "anthropic:claude-opus-5-5")).isEqualTo(Duration.ofMinutes(9));
        assertThat(defaults.attemptTimeout(stage, AgentRegistry.DETERMINISTIC)).isEqualTo(stage);
        assertThat(defaults.attemptTimeout(stage, AgentRegistry.FALLBACK_AGENT_ID)).isEqualTo(stage);
        assertThat(EngineConfig.builder().llmTimeoutScale(1.5).build().attemptTimeout(Duration.ofSeconds(10),
                "ollama:llama")).isEqualTo(Duration.ofSeconds(15));
        for (double invalid : new double[] {0.5, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> EngineConfig.builder().llmTimeoutScale(invalid).build()).as("scale " + invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
