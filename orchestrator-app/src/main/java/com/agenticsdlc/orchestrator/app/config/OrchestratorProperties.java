package com.agenticsdlc.orchestrator.app.config;

import java.nio.file.Path;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated configuration under the {@code sdlc.*} prefix (LLM providers live under {@code sdlc.llm.*}, see
 * {@code LlmProperties}). Invalid values fail application startup.
 *
 * @param workspace the repository workspace the orchestrator reasons over
 * @param engine engine tuning
 * @param security API protection
 * @param console operator console
 * @param agents agent provider selection
 */
@Validated
@ConfigurationProperties(prefix = "sdlc")
public record OrchestratorProperties(
        @DefaultValue @Valid @NotNull Workspace workspace,
        @DefaultValue @Valid @NotNull Engine engine,
        @DefaultValue @Valid @NotNull Security security,
        @DefaultValue @Valid @NotNull Console console,
        @DefaultValue @Valid @NotNull Agents agents) {

    /**
     * Workspace settings.
     *
     * @param root repository root to index; when unset it is detected by walking up from the working directory
     *             to a directory containing {@code pom.xml} and {@code url-shortener}
     * @param enabled whether the workspace is indexed at all (disabled = every run behaves like workspace NONE)
     */
    public record Workspace(@Nullable Path root, @DefaultValue("true") boolean enabled) {

        /**
         * Returns the configured root, or the detected repository root.
         *
         * @return absolute, normalized root
         */
        public Path resolvedRoot() {
            return root != null ? root.toAbsolutePath().normalize()
                    : WorkspaceRootDetector.detect(Path.of("").toAbsolutePath());
        }
    }

    /**
     * Engine settings (mapped onto {@code EngineConfig}).
     *
     * @param ambiguityThreshold ambiguity score at or above which intake asks for clarification
     * @param sandboxEnabled whether implementation materializes change sets into a sandbox copy of the workspace
     * @param verifyBuildEnabled whether verification runs the real build inside the sandbox
     * @param backoffScale multiplier applied to retry backoffs (0 makes retries immediate, used by tests)
     * @param sandboxRoot directory for sandboxes
     */
    public record Engine(
            @DefaultValue("0.5") @DecimalMin("0.0") @DecimalMax("1.0") double ambiguityThreshold,
            @DefaultValue("false") boolean sandboxEnabled,
            @DefaultValue("false") boolean verifyBuildEnabled,
            @DefaultValue("1.0") @DecimalMin("0.0") double backoffScale,
            @DefaultValue("sandbox") @NotNull Path sandboxRoot) {
    }

    /**
     * API protection.
     *
     * @param apiKey when non-blank, every {@code /api/**} request must carry it in {@code X-Api-Key}
     */
    public record Security(@DefaultValue("") String apiKey) {

        /**
         * Returns whether API-key authentication is enabled.
         *
         * @return true when a key is configured
         */
        public boolean apiKeyEnabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * Operator console.
     *
     * @param enabled whether the static console is served at {@code /}
     */
    public record Console(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Agent provider selection.
     *
     * @param defaultProvider provider used when a run request names none ({@code deterministic}, {@code anthropic}
     *                        or {@code ollama})
     */
    public record Agents(@DefaultValue("deterministic") @NotBlank String defaultProvider) {
    }
}
