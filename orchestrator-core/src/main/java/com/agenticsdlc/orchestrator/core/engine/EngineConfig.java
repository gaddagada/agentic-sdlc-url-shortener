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

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
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
 * @param buildCommand the fixed command of sandbox builds (default {@value AgentSettings#DEFAULT_BUILD_COMMAND});
 *                     the {@code buildCommand} an agent writes into a change set is informational only, so an
 *                     agent can never choose what runs in the sandbox
 * @param llmTimeoutScale factor applied to a stage's timeout when the active agent is an LLM provider agent (not
 *                        the deterministic agent or the deterministic fallback), so that long streamed outputs
 *                        (for example 32k tokens) are cut off by the provider limits rather than by the stage
 *                        timeout (default {@value #DEFAULT_LLM_TIMEOUT_SCALE}; at least 1.0)
 * @param sourceContextPerFileChars maximum characters of the source of one impacted file in a stage context
 *                                  (default {@value #DEFAULT_SOURCE_CONTEXT_PER_FILE_CHARS}); see
 *                                  {@link com.agenticsdlc.orchestrator.core.agent.StageContext#impactedSources()}
 * @param sourceContextTotalChars maximum characters of all impacted sources of one stage context (default
 *                                {@value #DEFAULT_SOURCE_CONTEXT_TOTAL_CHARS})
 * @param providerCircuitBreaker engine-wide circuit breaker per LLM provider (default
 *                               {@link ProviderCircuitBreaker#DEFAULTS}: open after 3 consecutive failures, 10 min
 *                               cooldown)
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
        @Nullable ScheduledExecutorService retryScheduler,
        String buildCommand,
        double llmTimeoutScale,
        int sourceContextPerFileChars,
        int sourceContextTotalChars,
        ProviderCircuitBreaker providerCircuitBreaker) {

    /** Default {@link #llmTimeoutScale()}. */
    public static final double DEFAULT_LLM_TIMEOUT_SCALE = 3.0;

    /** Default {@link #sourceContextPerFileChars()}. */
    public static final int DEFAULT_SOURCE_CONTEXT_PER_FILE_CHARS = 20_000;

    /** Default {@link #sourceContextTotalChars()}. */
    public static final int DEFAULT_SOURCE_CONTEXT_TOTAL_CHARS = 120_000;

    /**
     * Settings of the engine-wide circuit breaker per LLM provider. Provider-attributable failures are TRANSIENT,
     * TIMEOUT and INVALID_OUTPUT failures of attempts that called an agent of the provider (injected faults and the
     * deterministic agents never count). After {@code failureThreshold} consecutive such failures (across stages
     * and runs) the circuit opens: new attempts for that provider run the deterministic agent of their stage
     * instead (audited as FALLBACK_ENGAGED with reason {@code circuit open}). After {@code cooldown} the next
     * attempt tries the provider again (half-open); a success closes the circuit, another failure re-opens it.
     *
     * @param failureThreshold consecutive provider-attributable failures that open the circuit (default 3; 0
     *                         disables the breaker)
     * @param cooldown how long an open circuit routes attempts to the deterministic agents (default 10 min)
     */
    public record ProviderCircuitBreaker(int failureThreshold, Duration cooldown) {

        /** Default settings: 3 consecutive failures, 10 minute cooldown. */
        public static final ProviderCircuitBreaker DEFAULTS = new ProviderCircuitBreaker(3, Duration.ofMinutes(10));

        /** Settings that never open a circuit. */
        public static final ProviderCircuitBreaker DISABLED = new ProviderCircuitBreaker(0, Duration.ofMinutes(10));

        /**
         * Validates the settings.
         */
        public ProviderCircuitBreaker {
            Objects.requireNonNull(cooldown, "cooldown");
            if (failureThreshold < 0) {
                throw new IllegalArgumentException("failureThreshold must not be negative");
            }
            if (cooldown.isNegative() || cooldown.isZero()) {
                throw new IllegalArgumentException("cooldown must be positive");
            }
        }

        /**
         * Returns whether the breaker can open at all.
         *
         * @return false when {@code failureThreshold} is 0
         */
        public boolean enabled() {
            return failureThreshold > 0;
        }
    }

    /**
     * Validates the configuration.
     */
    public EngineConfig {
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(sandboxRoot, "sandboxRoot");
        Objects.requireNonNull(buildTimeout, "buildTimeout");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(workspaceIndex, "workspaceIndex");
        Objects.requireNonNull(buildCommand, "buildCommand");
        compensationHandlers = List.copyOf(compensationHandlers);
        if (ambiguityThreshold < 0.0 || ambiguityThreshold > 1.0) {
            throw new IllegalArgumentException("ambiguityThreshold must be within 0..1");
        }
        if (backoffScale < 0.0) {
            throw new IllegalArgumentException("backoffScale must not be negative");
        }
        if (buildCommand.isBlank()) {
            throw new IllegalArgumentException("buildCommand must not be blank");
        }
        if (!(llmTimeoutScale >= 1.0) || Double.isInfinite(llmTimeoutScale)) {
            throw new IllegalArgumentException("llmTimeoutScale must be a finite value of at least 1.0");
        }
        Objects.requireNonNull(providerCircuitBreaker, "providerCircuitBreaker");
        if (sourceContextPerFileChars < 1 || sourceContextTotalChars < 1) {
            throw new IllegalArgumentException("source context limits must be positive");
        }
    }

    /**
     * Creates a configuration with the default source context limits and provider circuit breaker (the canonical
     * constructor before they existed).
     *
     * @param ambiguityThreshold ambiguity threshold
     * @param backoffScale backoff scale
     * @param maxBackoff backoff cap
     * @param sandboxEnabled whether sandboxes are enabled
     * @param verifyBuildEnabled whether sandbox builds are enabled
     * @param sandboxRoot sandbox root
     * @param workspaceRoot workspace root, or null
     * @param buildTimeout build timeout
     * @param clock clock
     * @param workspaceIndex codebase index supplier
     * @param compensationHandlers compensation handlers
     * @param listener listener, or null
     * @param stageExecutor stage executor, or null
     * @param retryScheduler retry scheduler, or null
     * @param buildCommand sandbox build command
     * @param llmTimeoutScale LLM timeout scale
     */
    public EngineConfig(double ambiguityThreshold, double backoffScale, Duration maxBackoff, boolean sandboxEnabled,
                        boolean verifyBuildEnabled, Path sandboxRoot, @Nullable Path workspaceRoot,
                        Duration buildTimeout, Clock clock, Supplier<@Nullable CodebaseIndex> workspaceIndex,
                        List<CompensationHandler> compensationHandlers, @Nullable EngineListener listener,
                        @Nullable ExecutorService stageExecutor, @Nullable ScheduledExecutorService retryScheduler,
                        String buildCommand, double llmTimeoutScale) {
        this(ambiguityThreshold, backoffScale, maxBackoff, sandboxEnabled, verifyBuildEnabled, sandboxRoot,
                workspaceRoot, buildTimeout, clock, workspaceIndex, compensationHandlers, listener, stageExecutor,
                retryScheduler, buildCommand, llmTimeoutScale, DEFAULT_SOURCE_CONTEXT_PER_FILE_CHARS,
                DEFAULT_SOURCE_CONTEXT_TOTAL_CHARS, ProviderCircuitBreaker.DEFAULTS);
    }

    /**
     * Creates a configuration with the default build command ({@value AgentSettings#DEFAULT_BUILD_COMMAND}) and
     * the default LLM timeout scale ({@value #DEFAULT_LLM_TIMEOUT_SCALE}).
     *
     * @param ambiguityThreshold ambiguity threshold
     * @param backoffScale backoff scale
     * @param maxBackoff backoff cap
     * @param sandboxEnabled whether sandboxes are enabled
     * @param verifyBuildEnabled whether sandbox builds are enabled
     * @param sandboxRoot sandbox root
     * @param workspaceRoot workspace root, or null
     * @param buildTimeout build timeout
     * @param clock clock
     * @param workspaceIndex codebase index supplier
     * @param compensationHandlers compensation handlers
     * @param listener listener, or null
     * @param stageExecutor stage executor, or null
     * @param retryScheduler retry scheduler, or null
     */
    public EngineConfig(double ambiguityThreshold, double backoffScale, Duration maxBackoff, boolean sandboxEnabled,
                        boolean verifyBuildEnabled, Path sandboxRoot, @Nullable Path workspaceRoot,
                        Duration buildTimeout, Clock clock, Supplier<@Nullable CodebaseIndex> workspaceIndex,
                        List<CompensationHandler> compensationHandlers, @Nullable EngineListener listener,
                        @Nullable ExecutorService stageExecutor, @Nullable ScheduledExecutorService retryScheduler) {
        this(ambiguityThreshold, backoffScale, maxBackoff, sandboxEnabled, verifyBuildEnabled, sandboxRoot,
                workspaceRoot, buildTimeout, clock, workspaceIndex, compensationHandlers, listener, stageExecutor,
                retryScheduler, AgentSettings.DEFAULT_BUILD_COMMAND, DEFAULT_LLM_TIMEOUT_SCALE);
    }

    /**
     * Returns the timeout of one attempt: the stage timeout, multiplied by {@link #llmTimeoutScale()} when the
     * attempt runs an LLM provider agent (any agent id other than {@code deterministic} and
     * {@code fallback:deterministic}).
     *
     * @param stageTimeout the stage's configured timeout
     * @param agentId id of the agent running the attempt
     * @return the effective attempt timeout
     */
    public Duration attemptTimeout(Duration stageTimeout, String agentId) {
        if (AgentRegistry.DETERMINISTIC.equals(agentId) || AgentRegistry.FALLBACK_AGENT_ID.equals(agentId)
                || llmTimeoutScale == 1.0) {
            return stageTimeout;
        }
        double scaled = stageTimeout.toNanos() * llmTimeoutScale;
        return Duration.ofNanos(scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(scaled));
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
        b.buildCommand = buildCommand;
        b.llmTimeoutScale = llmTimeoutScale;
        b.sourceContextPerFileChars = sourceContextPerFileChars;
        b.sourceContextTotalChars = sourceContextTotalChars;
        b.providerCircuitBreaker = providerCircuitBreaker;
        return b;
    }

    /**
     * Returns the settings passed to agents.
     *
     * @return agent settings
     */
    public AgentSettings agentSettings() {
        return new AgentSettings(ambiguityThreshold, sandboxEnabled, verifyBuildEnabled, workspaceRoot, sandboxRoot,
                buildTimeout, buildCommand);
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
        private String buildCommand = AgentSettings.DEFAULT_BUILD_COMMAND;
        private double llmTimeoutScale = DEFAULT_LLM_TIMEOUT_SCALE;
        private int sourceContextPerFileChars = DEFAULT_SOURCE_CONTEXT_PER_FILE_CHARS;
        private int sourceContextTotalChars = DEFAULT_SOURCE_CONTEXT_TOTAL_CHARS;
        private ProviderCircuitBreaker providerCircuitBreaker = ProviderCircuitBreaker.DEFAULTS;

        private Builder() {
        }

        /**
         * Sets the maximum characters of the source of one impacted file in a stage context.
         *
         * @param value characters (at least 1)
         * @return this builder
         */
        public Builder sourceContextPerFileChars(int value) {
            this.sourceContextPerFileChars = value;
            return this;
        }

        /**
         * Sets the maximum characters of all impacted sources of one stage context.
         *
         * @param value characters (at least 1)
         * @return this builder
         */
        public Builder sourceContextTotalChars(int value) {
            this.sourceContextTotalChars = value;
            return this;
        }

        /**
         * Sets the provider circuit breaker.
         *
         * @param value settings ({@link ProviderCircuitBreaker#DISABLED} never opens a circuit)
         * @return this builder
         */
        public Builder providerCircuitBreaker(ProviderCircuitBreaker value) {
            this.providerCircuitBreaker = value;
            return this;
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
         * Sets the fixed sandbox build command (agent-supplied build commands are never executed).
         *
         * @param value Maven command, for example {@code ./mvnw -B -ntp -q verify}
         * @return this builder
         */
        public Builder buildCommand(String value) {
            this.buildCommand = value;
            return this;
        }

        /**
         * Sets the factor applied to stage timeouts for LLM provider agents.
         *
         * @param value factor of at least 1.0 (1.0 disables scaling)
         * @return this builder
         */
        public Builder llmTimeoutScale(double value) {
            this.llmTimeoutScale = value;
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
                    stageExecutor, retryScheduler, buildCommand, llmTimeoutScale, sourceContextPerFileChars,
                    sourceContextTotalChars, providerCircuitBreaker);
        }
    }
}
