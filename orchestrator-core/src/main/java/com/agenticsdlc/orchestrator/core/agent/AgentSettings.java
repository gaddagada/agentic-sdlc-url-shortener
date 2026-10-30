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
 */
public record AgentSettings(double ambiguityThreshold, boolean sandboxEnabled, boolean verifyBuildEnabled,
                            @Nullable Path workspaceRoot, Path sandboxRoot, Duration buildTimeout,
                            String buildCommand) {

    /** Default sandbox build command. */
    public static final String DEFAULT_BUILD_COMMAND = "./mvnw -B -ntp -q verify";

    /**
     * Validates settings.
     */
    public AgentSettings {
        Objects.requireNonNull(sandboxRoot, "sandboxRoot");
        Objects.requireNonNull(buildTimeout, "buildTimeout");
        Objects.requireNonNull(buildCommand, "buildCommand");
        if (ambiguityThreshold < 0.0 || ambiguityThreshold > 1.0) {
            throw new IllegalArgumentException("ambiguityThreshold must be within 0..1");
        }
        if (buildCommand.isBlank()) {
            throw new IllegalArgumentException("buildCommand must not be blank");
        }
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
     * Returns default settings (threshold 0.5, sandbox and build disabled, default build command).
     *
     * @return defaults
     */
    public static AgentSettings defaults() {
        return new AgentSettings(0.5, false, false, null, Path.of("sandbox"), Duration.ofMinutes(10));
    }
}
