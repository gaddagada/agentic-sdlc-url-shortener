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
 */
public record AgentSettings(double ambiguityThreshold, boolean sandboxEnabled, boolean verifyBuildEnabled,
                            @Nullable Path workspaceRoot, Path sandboxRoot, Duration buildTimeout) {

    /**
     * Validates settings.
     */
    public AgentSettings {
        Objects.requireNonNull(sandboxRoot, "sandboxRoot");
        Objects.requireNonNull(buildTimeout, "buildTimeout");
        if (ambiguityThreshold < 0.0 || ambiguityThreshold > 1.0) {
            throw new IllegalArgumentException("ambiguityThreshold must be within 0..1");
        }
    }

    /**
     * Returns default settings (threshold 0.5, sandbox and build disabled).
     *
     * @return defaults
     */
    public static AgentSettings defaults() {
        return new AgentSettings(0.5, false, false, null, Path.of("sandbox"), Duration.ofMinutes(10));
    }
}
