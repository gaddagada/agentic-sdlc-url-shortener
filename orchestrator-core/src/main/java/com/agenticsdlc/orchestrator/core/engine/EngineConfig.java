package com.agenticsdlc.orchestrator.core.engine;

import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.UnsignedChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.metrics.LlmPriceBook;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;
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
 * @param changeRecordSigner signs the release change record when the run completes and the record is finalized with
 *                           the approvals (default {@link UnsignedChangeRecordSigner}: no signature)
 * @param nodeId id of this engine node, used as lease owner and recorded in RUN_CREATED, RUN_RESUMED, REPLAN,
 *               RUN_INTERRUPTED and STAGE_STARTED audit data (default {@link #defaultNodeId()}: host name, process
 *               id and a random suffix, computed once per JVM; at most {@value #MAX_NODE_ID_LENGTH} characters). It
 *               must be unique per running engine process: an id reused by a restarted process makes the unexpired
 *               leases of its previous incarnation look live (and blocks their recovery) until they expire
 * @param leaseProvider grants run leases so that several nodes can share one store (default: a new
 *                      {@link InMemoryRunLeaseProvider} on {@code clock}, which only coordinates this engine)
 * @param leaseTtl lease duration; the lease is renewed at every mutation and every {@code leaseTtl / 3} while an
 *                 agent attempt runs (default {@value #DEFAULT_LEASE_TTL_SECONDS} s); it should exceed the longest
 *                 retry backoff ({@code maxBackoff * backoffScale}), during which nothing renews it
 * @param registryRetention how long a run that is not executing on this node (WAITING_HUMAN, SAFE_STOPPED,
 *                          COMPLETED, ABORTED) stays cached in memory after its last change (default 1 h; zero evicts
 *                          such runs right after each command); evicted runs are read from and rehydrated from the
 *                          store on demand
 * @param registryMaxTerminalRuns maximum number of COMPLETED and ABORTED runs cached in memory; beyond it the
 *                                least recently ended ones are evicted (default
 *                                {@value #DEFAULT_REGISTRY_MAX_TERMINAL_RUNS})
 * @param priceBook prices used to estimate the cost of every LLM call when its usage is recorded (default
 *                  {@link LlmPriceBook#EMPTY}: no prices, every call is unpriced and run costs are unknown); prices
 *                  are operator configuration, the core ships none
 * @param dependencyScanCommand optional external dependency scanner (for example an OWASP dependency-check Maven
 *                              goal) run by the security review in the run's sandbox, through the same
 *                              {@link com.agenticsdlc.orchestrator.core.workspace.BuildRunner} and allow-list as
 *                              {@code buildCommand} (Maven commands only); only its exit status and the first 2000
 *                              characters of its captured output are recorded (default null: no external scan; the
 *                              built-in dependency inventory always runs for REPO runs)
 * @param dependencyScanTimeout timeout of the dependency scan (default 5 min)
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
        ProviderCircuitBreaker providerCircuitBreaker,
        ChangeRecordSigner changeRecordSigner,
        String nodeId,
        RunLeaseProvider leaseProvider,
        Duration leaseTtl,
        Duration registryRetention,
        int registryMaxTerminalRuns,
        LlmPriceBook priceBook,
        @Nullable String dependencyScanCommand,
        Duration dependencyScanTimeout) {

    /** Default {@link #dependencyScanTimeout()}. */
    public static final Duration DEFAULT_DEPENDENCY_SCAN_TIMEOUT = AgentSettings.DEFAULT_DEPENDENCY_SCAN_TIMEOUT;

    /** Default {@link #llmTimeoutScale()}. */
    public static final double DEFAULT_LLM_TIMEOUT_SCALE = 3.0;

    /** Default {@link #leaseTtl()} in seconds. */
    public static final long DEFAULT_LEASE_TTL_SECONDS = 60;

    /** Default {@link #leaseTtl()}. */
    public static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(DEFAULT_LEASE_TTL_SECONDS);

    /** Default {@link #registryRetention()}. */
    public static final Duration DEFAULT_REGISTRY_RETENTION = Duration.ofHours(1);

    /** Default {@link #registryMaxTerminalRuns()}. */
    public static final int DEFAULT_REGISTRY_MAX_TERMINAL_RUNS = 500;

    /** Maximum length of {@link #nodeId()} (it is stored in lease and audit columns). */
    public static final int MAX_NODE_ID_LENGTH = 128;

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
        Objects.requireNonNull(changeRecordSigner, "changeRecordSigner");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(leaseProvider, "leaseProvider");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        Objects.requireNonNull(registryRetention, "registryRetention");
        if (nodeId.isBlank() || nodeId.length() > MAX_NODE_ID_LENGTH) {
            throw new IllegalArgumentException("nodeId must be non-blank and at most " + MAX_NODE_ID_LENGTH
                    + " characters");
        }
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        if (registryRetention.isNegative()) {
            throw new IllegalArgumentException("registryRetention must not be negative");
        }
        if (registryMaxTerminalRuns < 0) {
            throw new IllegalArgumentException("registryMaxTerminalRuns must not be negative");
        }
        Objects.requireNonNull(priceBook, "priceBook");
        Objects.requireNonNull(dependencyScanTimeout, "dependencyScanTimeout");
        if (dependencyScanCommand != null && dependencyScanCommand.isBlank()) {
            dependencyScanCommand = null;
        }
        if (dependencyScanTimeout.isNegative() || dependencyScanTimeout.isZero()) {
            throw new IllegalArgumentException("dependencyScanTimeout must be positive");
        }
    }

    /**
     * Creates a configuration without prices and without a dependency scan command (the canonical constructor before
     * they existed): {@link LlmPriceBook#EMPTY}, no scan, default scan timeout.
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
     * @param sourceContextPerFileChars source context characters per file
     * @param sourceContextTotalChars source context characters in total
     * @param providerCircuitBreaker provider circuit breaker
     * @param changeRecordSigner change record signer
     * @param nodeId node id
     * @param leaseProvider lease provider
     * @param leaseTtl lease duration
     * @param registryRetention registry retention
     * @param registryMaxTerminalRuns maximum cached terminal runs
     */
    public EngineConfig(double ambiguityThreshold, double backoffScale, Duration maxBackoff, boolean sandboxEnabled,
                        boolean verifyBuildEnabled, Path sandboxRoot, @Nullable Path workspaceRoot,
                        Duration buildTimeout, Clock clock, Supplier<@Nullable CodebaseIndex> workspaceIndex,
                        List<CompensationHandler> compensationHandlers, @Nullable EngineListener listener,
                        @Nullable ExecutorService stageExecutor, @Nullable ScheduledExecutorService retryScheduler,
                        String buildCommand, double llmTimeoutScale, int sourceContextPerFileChars,
                        int sourceContextTotalChars, ProviderCircuitBreaker providerCircuitBreaker,
                        ChangeRecordSigner changeRecordSigner, String nodeId, RunLeaseProvider leaseProvider,
                        Duration leaseTtl, Duration registryRetention, int registryMaxTerminalRuns) {
        this(ambiguityThreshold, backoffScale, maxBackoff, sandboxEnabled, verifyBuildEnabled, sandboxRoot,
                workspaceRoot, buildTimeout, clock, workspaceIndex, compensationHandlers, listener, stageExecutor,
                retryScheduler, buildCommand, llmTimeoutScale, sourceContextPerFileChars, sourceContextTotalChars,
                providerCircuitBreaker, changeRecordSigner, nodeId, leaseProvider, leaseTtl, registryRetention,
                registryMaxTerminalRuns, LlmPriceBook.EMPTY, null, DEFAULT_DEPENDENCY_SCAN_TIMEOUT);
    }

    /**
     * Creates a configuration with the default change record signer, node id, lease provider (in memory, on
     * {@code clock}), lease TTL and registry retention (the canonical constructor before they existed).
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
     * @param sourceContextPerFileChars source context characters per file
     * @param sourceContextTotalChars source context characters in total
     * @param providerCircuitBreaker provider circuit breaker
     */
    public EngineConfig(double ambiguityThreshold, double backoffScale, Duration maxBackoff, boolean sandboxEnabled,
                        boolean verifyBuildEnabled, Path sandboxRoot, @Nullable Path workspaceRoot,
                        Duration buildTimeout, Clock clock, Supplier<@Nullable CodebaseIndex> workspaceIndex,
                        List<CompensationHandler> compensationHandlers, @Nullable EngineListener listener,
                        @Nullable ExecutorService stageExecutor, @Nullable ScheduledExecutorService retryScheduler,
                        String buildCommand, double llmTimeoutScale, int sourceContextPerFileChars,
                        int sourceContextTotalChars, ProviderCircuitBreaker providerCircuitBreaker) {
        this(ambiguityThreshold, backoffScale, maxBackoff, sandboxEnabled, verifyBuildEnabled, sandboxRoot,
                workspaceRoot, buildTimeout, clock, workspaceIndex, compensationHandlers, listener, stageExecutor,
                retryScheduler, buildCommand, llmTimeoutScale, sourceContextPerFileChars, sourceContextTotalChars,
                providerCircuitBreaker, UnsignedChangeRecordSigner.INSTANCE, defaultNodeId(),
                new InMemoryRunLeaseProvider(Objects.requireNonNull(clock, "clock")), DEFAULT_LEASE_TTL,
                DEFAULT_REGISTRY_RETENTION, DEFAULT_REGISTRY_MAX_TERMINAL_RUNS);
    }

    /**
     * Returns the default node id of this JVM: the host name, the process id and a random six-hex-digit suffix
     * (for example {@code build-host-4711-3fa2c9}), computed once and then shared by every engine of the JVM.
     * Characters other than letters, digits, {@code .}, {@code _} and {@code -} are replaced by {@code -}. Engines
     * that share a lease provider within one JVM (tests) must be given distinct node ids explicitly.
     *
     * @return the default node id
     */
    public static String defaultNodeId() {
        return DefaultNodeId.VALUE;
    }

    /** Lazily computed default node id (host name resolution may be slow, so it happens once, on first use). */
    private static final class DefaultNodeId {
        static final String VALUE = compute();

        private static String compute() {
            String host = System.getenv("HOSTNAME");
            if (host == null || host.isBlank()) {
                host = System.getenv("COMPUTERNAME");
            }
            if (host == null || host.isBlank()) {
                try {
                    host = InetAddress.getLocalHost().getHostName();
                } catch (Exception e) {
                    host = "localhost";
                }
            }
            String sanitized = host.strip().replaceAll("[^A-Za-z0-9._-]", "-");
            if (sanitized.isEmpty()) {
                sanitized = "localhost";
            }
            if (sanitized.length() > 64) {
                sanitized = sanitized.substring(0, 64);
            }
            byte[] suffix = new byte[3];
            new SecureRandom().nextBytes(suffix);
            return sanitized + "-" + ProcessHandle.current().pid() + "-" + HexFormat.of().formatHex(suffix);
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
        b.changeRecordSigner = changeRecordSigner;
        b.nodeId = nodeId;
        b.leaseProvider = leaseProvider;
        b.leaseTtl = leaseTtl;
        b.registryRetention = registryRetention;
        b.registryMaxTerminalRuns = registryMaxTerminalRuns;
        b.priceBook = priceBook;
        b.dependencyScanCommand = dependencyScanCommand;
        b.dependencyScanTimeout = dependencyScanTimeout;
        return b;
    }

    /**
     * Returns the settings passed to agents.
     *
     * @return agent settings
     */
    public AgentSettings agentSettings() {
        return new AgentSettings(ambiguityThreshold, sandboxEnabled, verifyBuildEnabled, workspaceRoot, sandboxRoot,
                buildTimeout, buildCommand, dependencyScanCommand, dependencyScanTimeout);
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
        private ChangeRecordSigner changeRecordSigner = UnsignedChangeRecordSigner.INSTANCE;
        private @Nullable String nodeId;
        private @Nullable RunLeaseProvider leaseProvider;
        private Duration leaseTtl = DEFAULT_LEASE_TTL;
        private Duration registryRetention = DEFAULT_REGISTRY_RETENTION;
        private int registryMaxTerminalRuns = DEFAULT_REGISTRY_MAX_TERMINAL_RUNS;
        private LlmPriceBook priceBook = LlmPriceBook.EMPTY;
        private @Nullable String dependencyScanCommand;
        private Duration dependencyScanTimeout = DEFAULT_DEPENDENCY_SCAN_TIMEOUT;

        private Builder() {
        }

        /**
         * Sets the LLM price book used to estimate call costs.
         *
         * @param value the price book ({@link LlmPriceBook#EMPTY} prices nothing)
         * @return this builder
         */
        public Builder priceBook(LlmPriceBook value) {
            this.priceBook = value;
            return this;
        }

        /**
         * Sets the optional external dependency scan command run by the security review in the run's sandbox.
         *
         * @param value a command accepted by the build runner (Maven commands with plain arguments), or null for none
         * @return this builder
         */
        public Builder dependencyScanCommand(@Nullable String value) {
            this.dependencyScanCommand = value;
            return this;
        }

        /**
         * Sets the dependency scan timeout.
         *
         * @param value positive duration
         * @return this builder
         */
        public Builder dependencyScanTimeout(Duration value) {
            this.dependencyScanTimeout = value;
            return this;
        }

        /**
         * Sets the signer of finalized release change records.
         *
         * @param value signer ({@link UnsignedChangeRecordSigner} attaches no signature)
         * @return this builder
         */
        public Builder changeRecordSigner(ChangeRecordSigner value) {
            this.changeRecordSigner = value;
            return this;
        }

        /**
         * Sets the id of this engine node (lease owner, audit data).
         *
         * @param value node id (not blank, at most {@value EngineConfig#MAX_NODE_ID_LENGTH} characters), or null for
         *              {@link EngineConfig#defaultNodeId()}
         * @return this builder
         */
        public Builder nodeId(@Nullable String value) {
            this.nodeId = value;
            return this;
        }

        /**
         * Sets the run lease provider; nodes that share a run store must share a lease provider too.
         *
         * @param value provider, or null for a new {@link InMemoryRunLeaseProvider} on the configured clock
         * @return this builder
         */
        public Builder leaseProvider(@Nullable RunLeaseProvider value) {
            this.leaseProvider = value;
            return this;
        }

        /**
         * Sets the lease duration.
         *
         * @param value positive duration
         * @return this builder
         */
        public Builder leaseTtl(Duration value) {
            this.leaseTtl = value;
            return this;
        }

        /**
         * Sets how long a run that is not executing on this node stays cached after its last change.
         *
         * @param value non-negative duration
         * @return this builder
         */
        public Builder registryRetention(Duration value) {
            this.registryRetention = value;
            return this;
        }

        /**
         * Sets the maximum number of terminal runs cached in memory.
         *
         * @param value non-negative count
         * @return this builder
         */
        public Builder registryMaxTerminalRuns(int value) {
            this.registryMaxTerminalRuns = value;
            return this;
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
                    sourceContextTotalChars, providerCircuitBreaker, changeRecordSigner,
                    nodeId != null ? nodeId : defaultNodeId(),
                    leaseProvider != null ? leaseProvider
                            : new InMemoryRunLeaseProvider(Objects.requireNonNull(clock, "clock")),
                    leaseTtl, registryRetention, registryMaxTerminalRuns, priceBook, dependencyScanCommand,
                    dependencyScanTimeout);
        }
    }
}
