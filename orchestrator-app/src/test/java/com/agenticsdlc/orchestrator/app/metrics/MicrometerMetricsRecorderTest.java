package com.agenticsdlc.orchestrator.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MicrometerMetricsRecorder} registers the meters of the engineering specification (section 6.7).
 */
class MicrometerMetricsRecorderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    @Test
    void recordsEveryEngineSignal() {
        recorder.stageCompleted("design", StageStatus.SUCCEEDED, 120);
        recorder.stageCompleted("design", StageStatus.SUCCEEDED, 80);
        recorder.retry("implement");
        recorder.retry("implement");
        recorder.fallback("test-design");
        recorder.rollback("run-1");
        recorder.runCompleted(5000, 1200);
        recorder.agentCall("ollama", AgentRole.ARCHITECTURE, 900, 700, 300);
        recorder.agentCall("deterministic", AgentRole.PLANNING, 2, 0, 0);
        recorder.approvalDecision(4000, true);
        recorder.approvalDecision(1000, false);

        assertThat(registry.get("sdlc.stage.duration").tags("stage", "design", "status", "SUCCEEDED").timer()
                .count()).isEqualTo(2);
        assertThat(registry.get("sdlc.stage.duration").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);
        assertThat(registry.get("sdlc.stage.retries").tag("stage", "implement").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("sdlc.stage.fallbacks").tag("stage", "test-design").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("sdlc.run.rollbacks").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("sdlc.run.completed").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5000.0);
        assertThat(registry.get("sdlc.run.active").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1200.0);
        assertThat(registry.get("sdlc.agent.call").tags("provider", "ollama", "role", "architecture").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("sdlc.llm.tokens").tags("provider", "ollama", "direction", "input").counter().count())
                .isEqualTo(700.0);
        assertThat(registry.get("sdlc.llm.tokens").tags("provider", "ollama", "direction", "output").counter()
                .count()).isEqualTo(300.0);
        assertThat(registry.find("sdlc.llm.tokens").tag("provider", "deterministic").counter())
                .as("no token counters for providers without usage").isNull();
        assertThat(registry.get("sdlc.approval.decision").tag("outcome", "approved").timer().count()).isEqualTo(1);
        assertThat(registry.get("sdlc.approval.decision").tag("outcome", "rejected").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsPromptCacheReadsAndWritesPerProviderAndModel() {
        recorder.cacheUsage("anthropic", "claude-opus-5-5", 4000, 1200);
        recorder.cacheUsage("anthropic", "claude-opus-5-5", 4000, 0);
        recorder.cacheUsage("anthropic", "claude-sonnet-5", 0, 0);

        assertThat(registry.get("sdlc.llm.cache.read.tokens").tags("provider", "anthropic", "model", "claude-opus-5-5")
                .counter().count()).isEqualTo(8000.0);
        assertThat(registry.get("sdlc.llm.cache.creation.tokens").tags("provider", "anthropic", "model",
                "claude-opus-5-5").counter().count()).isEqualTo(1200.0);
        assertThat(registry.get("sdlc.llm.cache.read.tokens").tag("model", "claude-sonnet-5").counter().count())
                .as("registered at zero").isEqualTo(0.0);
    }
}
