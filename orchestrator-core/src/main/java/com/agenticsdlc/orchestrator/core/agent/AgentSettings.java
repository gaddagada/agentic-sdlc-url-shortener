package com.agenticsdlc.orchestrator.core.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Engine settings agents need, passed through the {@link StageContext}.
 *
 * @param ambiguityThreshold ambiguity score at or above which intake asks for clarification
 * @param sandboxEnabled whether the implementation agent materializes change sets into a sandbox
 * @param verifyBuildEnabled whether the verification agent runs a real build in the sandbox
 * @param workspaceRoot repository root (for sandboxes), or null
 * @param sandboxRoot directory under which sandboxes are created ({@code <sandboxRoot>/<runId>})
 * @param buildTimeout timeout of a sandbox build
 * @param buildCommand the fixed sandbox build command (engine configuration); the {@code buildCommand} an agent
 *                     writes into a change set is informational only and never executed
 * @param dependencyScanCommand optional external dependency scanner run by the security review in the run's sandbox
 *                              (engine configuration, same runner and allow-list as {@code buildCommand}), or null for
 *                              none
 * @param dependencyScanTimeout timeout of the dependency scan
 */
public record AgentSettings(double ambiguityThreshold, boolean sandboxEnabled, boolean verifyBuildEnabled,
                            @Nullable Path workspaceRoot, Path sandboxRoot, Duration buildTimeout,
                            String buildCommand, @Nullable String dependencyScanCommand,
                            Duration dependencyScanTimeout) {

    /** Default sandbox build command. */
    public static final String DEFAULT_BUILD_COMMAND = "./mvnw -B -ntp -q verify";

    /** Default {@link #dependencyScanTimeout()}. */
    public static final Duration DEFAULT_DEPENDENCY_SCAN_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Validates settings; a blank scan command becomes null.
     */
    public AgentSettings {
        Objects.requireNonNull(sandboxRoot, "sandboxRoot");
        Objects.requireNonNull(buildTimeout, "buildTimeout");
        Objects.requireNonNull(buildCommand, "buildCommand");
        Objects.requireNonNull(dependencyScanTimeout, "dependencyScanTimeout");
        if (ambiguityThreshold < 0.0 || ambiguityThreshold > 1.0) {
            throw new IllegalArgumentException("ambiguityThreshold must be within 0..1");
        }
        if (buildCommand.isBlank()) {
            throw new IllegalArgumentException("buildCommand must not be blank");
        }
        if (dependencyScanCommand != null && dependencyScanCommand.isBlank()) {
            dependencyScanCommand = null;
        }
        if (dependencyScanTimeout.isNegative() || dependencyScanTimeout.isZero()) {
            throw new IllegalArgumentException("dependencyScanTimeout must be positive");
        }
    }

    /**
     * Creates settings without a dependency scan command (the canonical constructor before it existed).
     *
     * @param ambiguityThreshold ambiguity threshold
     * @param sandboxEnabled whether sandboxes are enabled
     * @param verifyBuildEnabled whether sandbox builds are enabled
     * @param workspaceRoot repository root, or null
     * @param sandboxRoot sandbox root directory
     * @param buildTimeout sandbox build timeout
     * @param buildCommand sandbox build command
     */
    public AgentSettings(double ambiguityThreshold, boolean sandboxEnabled, boolean verifyBuildEnabled,
                         @Nullable Path workspaceRoot, Path sandboxRoot, Duration buildTimeout, String buildCommand) {
        this(ambiguityThreshold, sandboxEnabled, verifyBuildEnabled, workspaceRoot, sandboxRoot, buildTimeout,
                buildCommand, null, DEFAULT_DEPENDENCY_SCAN_TIMEOUT);
    }

    /**
     * Creates settings with the default build command ({@value #DEFAULT_BUILD_COMMAND}).
     *
     * @param ambiguityThreshold ambiguity threshold
     * @param sandboxEnabled whether sandboxes are enabled
     * @param verifyBuildEnabled whether sandbox builds are enabled
     * @param workspaceRoot repository root, or null
     * @param sandboxRoot sandbox root directory
     * @param buildTimeout sandbox build timeout
     */
    public AgentSettings(double ambiguityThreshold, boolean sandboxEnabled, boolean verifyBuildEnabled,
                         @Nullable Path workspaceRoot, Path sandboxRoot, Duration buildTimeout) {
        this(ambiguityThreshold, sandboxEnabled, verifyBuildEnabled, workspaceRoot, sandboxRoot, buildTimeout,
                DEFAULT_BUILD_COMMAND);
    }

    /**
     * Returns default settings (threshold 0.5, sandbox and build disabled, default build command, no dependency
     * scan).
     *
     * @return defaults
     */
    public static AgentSettings defaults() {
        return new AgentSettings(0.5, false, false, null, Path.of("sandbox"), Duration.ofMinutes(10));
    }

    /**
     * Returns a copy with a dependency scan command.
     *
     * @param command the scan command, or null for none
     * @param timeout the scan timeout
     * @return the copy
     */
    public AgentSettings withDependencyScan(@Nullable String command, Duration timeout) {
        return new AgentSettings(ambiguityThreshold, sandboxEnabled, verifyBuildEnabled, workspaceRoot, sandboxRoot,
                buildTimeout, buildCommand, command, timeout);
    }
}
