package com.agenticsdlc.orchestrator.core.engine;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Engine configuration.
 *
 * @param ambiguityThreshold ambiguity score at or above which intake asks for clarification (default 0.5)
 * @param backoffScale multiplier applied to retry backoffs (default 1.0; 0 makes retries immediate)
 * @param maxBackoff cap of a single retry backoff before scaling (default 30 s)
 * @param sandboxEnabled whether implementation materializes change sets into a sandbox (default false)
 * @param verifyBuildEnabled whether verification runs a real build in the sandbox (default false)
 * @param sandboxRoot directory for sandboxes (default {@code ./sandbox})
 * @param workspaceRoot repository root for sandboxes, or null
 * @param buildTimeout sandbox build timeout (default 10 min)
 * @param clock clock (default system UTC)
 * @param workspaceIndex supplier of the current codebase index (may return null; default none)
 * @param compensationHandlers compensation handlers by action (a DELETE_SANDBOX handler is always added)
 * @param listener optional run change listener
 * @param stageExecutor executor for stage attempts, or null for an engine-owned virtual-thread executor;
 *                      it must allow nested task submission (an attempt waits on a nested task)
 * @param retryScheduler scheduler for retry wake-ups, or null for an engine-owned single-thread scheduler
 */
public record EngineConfig(
        double ambiguityThreshold,
        double backoffScale,
        Duration maxBackoff,
        boolean sandboxEnabled,
        boolean verifyBuildEnabled,
        Path sandboxRoot,
        @Nullable Path workspaceRoot,
        Duration buildTimeout,
        Clock clock,
        Supplier<@Nullable CodebaseIndex> workspaceIndex,
        List<CompensationHandler> compensationHandlers,
        @Nullable EngineListener listener,
        @Nullable ExecutorService stageExecutor,
        @Nullable ScheduledExecutorService retryScheduler) {

    /**
     * Validates the configuration.
     */
    public EngineConfig {
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(sandboxRoot, "sandboxRoot");
        Objects.requireNonNull(buildTimeout, "buildTimeout");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(workspaceIndex, "workspaceIndex");
        compensationHandlers = List.copyOf(compensationHandlers);
        if (ambiguityThreshold < 0.0 || ambiguityThreshold > 1.0) {
            throw new IllegalArgumentException("ambiguityThreshold must be within 0..1");
        }
        if (backoffScale < 0.0) {
            throw new IllegalArgumentException("backoffScale must not be negative");
        }
    }

    /**
     * Returns the default configuration.
     *
     * @return defaults
     */
    public static EngineConfig defaults() {
        return builder().build();
    }

    /**
     * Returns a builder initialized with the defaults.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with this configuration.
     *
     * @return a builder
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.ambiguityThreshold = ambiguityThreshold;
        b.backoffScale = backoffScale;
        b.maxBackoff = maxBackoff;
        b.sandboxEnabled = sandboxEnabled;
        b.verifyBuildEnabled = verifyBuildEnabled;
        b.sandboxRoot = sandboxRoot;
        b.workspaceRoot = workspaceRoot;
        b.buildTimeout = buildTimeout;
        b.clock = clock;
        b.workspaceIndex = workspaceIndex;
        b.compensationHandlers = new ArrayList<>(compensationHandlers);
        b.listener = listener;
        b.stageExecutor = stageExecutor;
        b.retryScheduler = retryScheduler;
        return b;
    }

    /**
     * Returns the settings passed to agents.
     *
     * @return agent settings
     */
    public AgentSettings agentSettings() {
        return new AgentSettings(ambiguityThreshold, sandboxEnabled, verifyBuildEnabled, workspaceRoot, sandboxRoot,
                buildTimeout);
    }

    /**
     * Builder of {@link EngineConfig}.
     */
    public static final class Builder {
        private double ambiguityThreshold = 0.5;
        private double backoffScale = 1.0;
        private Duration maxBackoff = Duration.ofSeconds(30);
        private boolean sandboxEnabled;
        private boolean verifyBuildEnabled;
        private Path sandboxRoot = Path.of("sandbox");
        private @Nullable Path workspaceRoot;
        private Duration buildTimeout = Duration.ofMinutes(10);
        private Clock clock = Clock.systemUTC();
        private Supplier<@Nullable CodebaseIndex> workspaceIndex = () -> null;
        private List<CompensationHandler> compensationHandlers = new ArrayList<>();
        private @Nullable EngineListener listener;
        private @Nullable ExecutorService stageExecutor;
        private @Nullable ScheduledExecutorService retryScheduler;

        private Builder() {
        }

        /**
         * Sets the ambiguity threshold.
         *
         * @param value threshold within 0..1
         * @return this builder
         */
        public Builder ambiguityThreshold(double value) {
            this.ambiguityThreshold = value;
            return this;
        }

        /**
         * Sets the backoff scale (0 for immediate retries in tests).
         *
         * @param value scale
         * @return this builder
         */
        public Builder backoffScale(double value) {
            this.backoffScale = value;
            return this;
        }

        /**
         * Sets the maximum backoff before scaling.
         *
         * @param value cap
         * @return this builder
         */
        public Builder maxBackoff(Duration value) {
            this.maxBackoff = value;
            return this;
        }

        /**
         * Enables or disables sandboxes.
         *
         * @param value enabled
         * @return this builder
         */
        public Builder sandboxEnabled(boolean value) {
            this.sandboxEnabled = value;
            return this;
        }

        /**
         * Enables or disables sandbox builds during verification.
         *
         * @param value enabled
         * @return this builder
         */
        public Builder verifyBuildEnabled(boolean value) {
            this.verifyBuildEnabled = value;
            return this;
        }

        /**
         * Sets the sandbox root.
         *
         * @param value directory
         * @return this builder
         */
        public Builder sandboxRoot(Path value) {
            this.sandboxRoot = value;
            return this;
        }

        /**
         * Sets the workspace root.
         *
         * @param value directory or null
         * @return this builder
         */
        public Builder workspaceRoot(@Nullable Path value) {
            this.workspaceRoot = value;
            return this;
        }

        /**
         * Sets the sandbox build timeout.
         *
         * @param value timeout
         * @return this builder
         */
        public Builder buildTimeout(Duration value) {
            this.buildTimeout = value;
            return this;
        }

        /**
         * Sets the clock.
         *
         * @param value clock
         * @return this builder
         */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /**
         * Sets the codebase index supplier.
         *
         * @param value supplier (may return null)
         * @return this builder
         */
        public Builder workspaceIndex(Supplier<@Nullable CodebaseIndex> value) {
            this.workspaceIndex = value;
            return this;
        }

        /**
         * Adds a compensation handler.
         *
         * @param handler handler
         * @return this builder
         */
        public Builder compensationHandler(CompensationHandler handler) {
            this.compensationHandlers.add(handler);
            return this;
        }

        /**
         * Sets the listener.
         *
         * @param value listener or null
         * @return this builder
         */
        public Builder listener(@Nullable EngineListener value) {
            this.listener = value;
            return this;
        }

        /**
         * Sets the stage executor (the caller keeps ownership and must shut it down).
         *
         * @param value executor or null for an engine-owned virtual-thread executor
         * @return this builder
         */
        public Builder stageExecutor(@Nullable ExecutorService value) {
            this.stageExecutor = value;
            return this;
        }

        /**
         * Sets the retry scheduler (the caller keeps ownership and must shut it down).
         *
         * @param value scheduler or null for an engine-owned scheduler
         * @return this builder
         */
        public Builder retryScheduler(@Nullable ScheduledExecutorService value) {
            this.retryScheduler = value;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return the configuration
         */
        public EngineConfig build() {
            return new EngineConfig(ambiguityThreshold, backoffScale, maxBackoff, sandboxEnabled, verifyBuildEnabled,
                    sandboxRoot, workspaceRoot, buildTimeout, clock, workspaceIndex, compensationHandlers, listener,
                    stageExecutor, retryScheduler);
        }
    }
}
