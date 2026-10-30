package com.agenticsdlc.orchestrator.app.metrics;

import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.llm.PromptCacheListener;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer adapter of the engine's {@link MetricsRecorder} SPI (exposed through {@code /actuator/metrics}).
 *
 * <p>Meters: {@code sdlc.stage.duration} (timer; tags {@code stage}, {@code status}), {@code sdlc.stage.retries}
 * and {@code sdlc.stage.fallbacks} (counters; tag {@code stage}), {@code sdlc.run.rollbacks} (counter),
 * {@code sdlc.run.completed} and {@code sdlc.run.active} (timers of end-to-end and active time of completed runs),
 * {@code sdlc.agent.call} (timer; tags {@code provider}, {@code role}), {@code sdlc.llm.tokens} (counter; tags
 * {@code provider}, {@code direction}; input counts every prompt token, cached or not), {@code sdlc.llm.cache.read.tokens}
 * and {@code sdlc.llm.cache.creation.tokens} (counters of prompt-cache reads and writes; tags {@code provider},
 * {@code model}; reported by the LLM clients through {@link PromptCacheListener}) and {@code sdlc.approval.decision}
 * (timer; tag {@code outcome}). Run ids are
 * deliberately not used as tags (unbounded cardinality). The engine calls these methods while holding a run lock;
 * Micrometer meters are lock-free and cheap.</p>
 */
@Component
public class MicrometerMetricsRecorder implements MetricsRecorder, PromptCacheListener {

    private final MeterRegistry registry;

    /**
     * Creates the recorder.
     *
     * @param registry the meter registry
     */
    public MicrometerMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void stageCompleted(String stageId, StageStatus status, long durationMs) {
        Timer.builder("sdlc.stage.duration").description("Duration of stage attempts")
                .tag("stage", stageId).tag("status", status.name()).register(registry)
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    @Override
    public void retry(String stageId) {
        Counter.builder("sdlc.stage.retries").description("Stage retries scheduled").tag("stage", stageId)
                .register(registry).increment();
    }

    @Override
    public void fallback(String stageId) {
        Counter.builder("sdlc.stage.fallbacks").description("Deterministic fallbacks engaged").tag("stage", stageId)
                .register(registry).increment();
    }

    @Override
    public void rollback(String runId) {
        Counter.builder("sdlc.run.rollbacks").description("Rollbacks executed").register(registry).increment();
    }

    @Override
    public void runCompleted(long durationMs, long activeMs) {
        Timer.builder("sdlc.run.completed").description("End-to-end time of completed runs").register(registry)
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
        Timer.builder("sdlc.run.active").description("Active (non human-wait) time of completed runs")
                .register(registry).record(Duration.ofMillis(Math.max(0L, activeMs)));
    }

    @Override
    public void agentCall(String providerId, AgentRole role, long latencyMs, long inputTokens, long outputTokens) {
        Timer.builder("sdlc.agent.call").description("Agent call latency").tag("provider", providerId)
                .tag("role", role.name().toLowerCase(Locale.ROOT)).register(registry)
                .record(Duration.ofMillis(Math.max(0L, latencyMs)));
        if (inputTokens > 0) {
            tokens(providerId, "input").increment(inputTokens);
        }
        if (outputTokens > 0) {
            tokens(providerId, "output").increment(outputTokens);
        }
    }

    @Override
    public void approvalDecision(long decisionMs, boolean approved) {
        Timer.builder("sdlc.approval.decision").description("Time from approval request to human decision")
                .tag("outcome", approved ? "approved" : "rejected").register(registry)
                .record(Duration.ofMillis(Math.max(0L, decisionMs)));
    }

    @Override
    public void cacheUsage(String providerId, String model, long cacheReadTokens, long cacheCreationTokens) {
        Counter read = Counter.builder("sdlc.llm.cache.read.tokens").description("LLM input tokens read from the "
                + "prompt cache").baseUnit("tokens").tag("provider", providerId).tag("model", model).register(registry);
        Counter creation = Counter.builder("sdlc.llm.cache.creation.tokens").description("LLM input tokens written to "
                + "the prompt cache").baseUnit("tokens").tag("provider", providerId).tag("model", model)
                .register(registry);
        if (cacheReadTokens > 0) {
            read.increment(cacheReadTokens);
        }
        if (cacheCreationTokens > 0) {
            creation.increment(cacheCreationTokens);
        }
    }

    private Counter tokens(String providerId, String direction) {
        return Counter.builder("sdlc.llm.tokens").description("LLM tokens").tag("provider", providerId)
                .tag("direction", direction).register(registry);
    }
}
