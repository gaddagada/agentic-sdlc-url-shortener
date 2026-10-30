package com.agenticsdlc.orchestrator.app.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.agenticsdlc.orchestrator.core.audit.HmacChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;

/**
 * Typed, validated configuration under the {@code sdlc.*} prefix (LLM providers live under {@code sdlc.llm.*}, see
 * {@code LlmProperties}). Invalid values fail application startup.
 *
 * @param workspace the repository workspace the orchestrator reasons over
 * @param engine engine tuning
 * @param security API authentication and authorization ({@code open}, {@code api-key} or {@code oidc})
 * @param console operator console
 * @param agents agent provider selection
 * @param governance governance rules enforced by the control plane on top of the engine's policies
 * @param metrics the reliability metrics endpoint
 * @param policy the policy pack loaded next to the built-in rules
 * @param evaluation the evaluation endpoints
 */
@Validated
@ConfigurationProperties(prefix = "sdlc")
public record OrchestratorProperties(
        @DefaultValue @Valid @NotNull Workspace workspace,
        @DefaultValue @Valid @NotNull Engine engine,
        @DefaultValue @Valid @NotNull Security security,
        @DefaultValue @Valid @NotNull Console console,
        @DefaultValue @Valid @NotNull Agents agents,
        @DefaultValue @Valid @NotNull Governance governance,
        @DefaultValue @Valid @NotNull Metrics metrics,
        @DefaultValue @Valid @NotNull Policy policy,
        @DefaultValue @Valid @NotNull Evaluation evaluation) {

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
     * @param llmTimeoutScale multiplier applied to stage timeouts when an LLM provider (not the deterministic
     *                        agent) executes the attempt; streamed long outputs need more than the base timeout
     * @param buildCommand the only command the sandbox build runner executes (agent-supplied commands are ignored)
     * @param leaseProvider where run leases live: {@code jdbc} (the {@code run_leases} table, shared by every node
     *                      of one database) or {@code memory} (this process only; single node)
     * @param leaseTtl how long a run lease lasts unless renewed; it must exceed the longest retry backoff (the
     *                 engine's maximum backoff times {@code backoffScale}), because nothing renews a lease while a
     *                 retry waits
     * @param nodeId id of this engine node (lease owner, recorded in audit data); unset for
     *               {@link EngineConfig#defaultNodeId()} (host name, process id, random suffix). It must be unique
     *               per running process
     * @param registryRetention how long a run that is not executing on this node stays cached in memory after its
     *                          last change (zero evicts such runs right after each command)
     * @param registryMaxTerminalRuns maximum number of completed and aborted runs cached in memory
     * @param maintenanceInterval fixed delay of the engine maintenance task (recovery of runs whose node died once
     *                            their lease expired, eviction of idle runs from memory)
     * @param dependencyScanCommand optional external dependency scanner the security review runs in the run's sandbox
     *                              (for example an OWASP dependency-check Maven goal); it goes through the same build
     *                              runner and allow-list as {@code buildCommand} (Maven commands only), and only its
     *                              exit status and the first 2000 characters of its output are recorded. Unset: no
     *                              external scan (the built-in dependency inventory still runs for REPO runs)
     * @param dependencyScanTimeout timeout of the dependency scan
     */
    public record Engine(
            @DefaultValue("0.5") @DecimalMin("0.0") @DecimalMax("1.0") double ambiguityThreshold,
            @DefaultValue("false") boolean sandboxEnabled,
            @DefaultValue("false") boolean verifyBuildEnabled,
            @DefaultValue("1.0") @DecimalMin("0.0") double backoffScale,
            @DefaultValue("sandbox") @NotNull Path sandboxRoot,
            @DefaultValue("3.0") @DecimalMin("1.0") double llmTimeoutScale,
            @DefaultValue("./mvnw -B -ntp -q verify") @NotBlank String buildCommand,
            @DefaultValue("jdbc") @NotNull LeaseProvider leaseProvider,
            @DefaultValue("60s") @NotNull Duration leaseTtl,
            @Nullable @Pattern(regexp = "[A-Za-z0-9._:@-]{1,128}",
                    message = "must be 1 to 128 letters, digits or . _ : @ -") String nodeId,
            @DefaultValue("1h") @NotNull Duration registryRetention,
            @DefaultValue("500") @Min(0) int registryMaxTerminalRuns,
            @DefaultValue("30s") @NotNull Duration maintenanceInterval,
            @Nullable String dependencyScanCommand,
            @DefaultValue("5m") @NotNull Duration dependencyScanTimeout) {

        /**
         * Validates the durations.
         */
        public Engine {
            if (leaseTtl != null && (leaseTtl.isNegative() || leaseTtl.isZero())) {
                throw new IllegalArgumentException("sdlc.engine.lease-ttl must be positive (got " + leaseTtl + ")");
            }
            if (registryRetention != null && registryRetention.isNegative()) {
                throw new IllegalArgumentException("sdlc.engine.registry-retention must not be negative (got "
                        + registryRetention + ")");
            }
            if (maintenanceInterval != null && (maintenanceInterval.isNegative() || maintenanceInterval.isZero())) {
                throw new IllegalArgumentException("sdlc.engine.maintenance-interval must be positive (got "
                        + maintenanceInterval + ")");
            }
            if (dependencyScanTimeout != null && (dependencyScanTimeout.isNegative()
                    || dependencyScanTimeout.isZero())) {
                throw new IllegalArgumentException("sdlc.engine.dependency-scan-timeout must be positive (got "
                        + dependencyScanTimeout + ")");
            }
        }

        /**
         * Returns the dependency scan command, or null when none is configured (blank counts as none).
         *
         * @return the command, stripped, or null
         */
        public @Nullable String resolvedDependencyScanCommand() {
            return dependencyScanCommand == null || dependencyScanCommand.isBlank() ? null
                    : dependencyScanCommand.strip();
        }

        /**
         * Returns the node id in force: the configured one, else the engine's default node id of this JVM.
         *
         * @return the node id
         */
        public String resolvedNodeId() {
            return nodeId != null && !nodeId.isBlank() ? nodeId.strip() : EngineConfig.defaultNodeId();
        }

        /**
         * Where run leases are kept ({@code sdlc.engine.lease-provider}).
         */
        public enum LeaseProvider {
            /** The {@code run_leases} table of the control plane database: several nodes may share the database. */
            JDBC,
            /** In this process only: a single node (several nodes on one database would not see each other). */
            MEMORY
        }
    }

    /**
     * API authentication and authorization (ADR 0009 as amended by the security modes).
     *
     * @param mode how API callers are authenticated; when unset, {@link Mode#API_KEY} if {@code apiKey} is set (the
     *             behaviour before modes existed) and {@link Mode#OPEN} otherwise, see {@link #effectiveMode()}
     * @param apiKey the shared key every {@code /api/**} request must send in {@code X-Api-Key} in
     *               {@link Mode#API_KEY} mode (ignored in the other modes)
     * @param publicDocs whether the OpenAPI document ({@code /v3/api-docs/**}) and Swagger UI
     *                   ({@code /swagger-ui/**}) are served without authentication; false requires the same
     *                   authentication as a {@code GET /api/**} request
     * @param oidc JWT resource-server settings of {@link Mode#OIDC} mode
     */
    public record Security(
            @Nullable Mode mode,
            @DefaultValue("") String apiKey,
            @DefaultValue("true") boolean publicDocs,
            @DefaultValue @Valid @NotNull Oidc oidc) {

        /**
         * Returns the mode in force: the configured one, else {@code api-key} when a key is configured (backwards
         * compatibility with deployments that only set {@code sdlc.security.api-key}), else {@code open}.
         *
         * @return the effective mode
         */
        public Mode effectiveMode() {
            return Mode.resolve(mode, apiKey);
        }

        /**
         * Returns whether the mode was derived from the presence of an API key rather than configured.
         *
         * @return true when {@code sdlc.security.mode} is unset and a key is configured
         */
        public boolean modeInferredFromApiKey() {
            return mode == null && hasApiKey();
        }

        /**
         * Returns whether a non-blank API key is configured (whatever the mode).
         *
         * @return true when {@code sdlc.security.api-key} is set
         */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * Returns whether API-key authentication is in force.
         *
         * @return true in {@link Mode#API_KEY} mode
         */
        public boolean apiKeyEnabled() {
            return effectiveMode() == Mode.API_KEY;
        }

        /**
         * Describes the settings without the API key.
         *
         * @return a description safe to log
         */
        @Override
        public String toString() {
            return "Security[mode=" + mode + ", apiKey=" + (hasApiKey() ? "<redacted>" : "<none>") + ", publicDocs="
                    + publicDocs + ", oidc=" + oidc + "]";
        }

        /**
         * Security modes ({@code sdlc.security.mode}).
         */
        public enum Mode {
            /** No authentication: every caller holds every role (local evaluation; refused by the prod profile). */
            OPEN,
            /** A shared key in {@code X-Api-Key} on {@code /api/**}; a caller with the key holds every role. */
            API_KEY,
            /** OAuth 2.0 bearer JWTs verified against an OIDC provider; roles and identity come from the token. */
            OIDC;

            /**
             * Resolves the effective mode.
             *
             * @param configured the configured mode, or null
             * @param apiKey the configured API key, or null
             * @return {@code configured} when set, else {@link #API_KEY} for a non-blank key, else {@link #OPEN}
             */
            public static Mode resolve(@Nullable Mode configured, @Nullable String apiKey) {
                if (configured != null) {
                    return configured;
                }
                return apiKey != null && !apiKey.isBlank() ? API_KEY : OPEN;
            }

            /**
             * Parses a property value the way Spring Boot binds enums ({@code api-key}, {@code API_KEY} and
             * {@code apiKey} name the same mode).
             *
             * @param value the raw property value, or null
             * @return the mode, or null when the value is null or blank
             * @throws IllegalArgumentException when the value names no mode
             */
            public static @Nullable Mode parse(@Nullable String value) {
                if (value == null || value.isBlank()) {
                    return null;
                }
                String canonical = canonical(value);
                for (Mode mode : values()) {
                    if (canonical(mode.name()).equals(canonical)) {
                        return mode;
                    }
                }
                throw new IllegalArgumentException("unknown sdlc.security.mode '" + value.strip()
                        + "' (expected open, api-key or oidc)");
            }

            /**
             * Returns the property spelling of the mode.
             *
             * @return {@code open}, {@code api-key} or {@code oidc}
             */
            public String propertyValue() {
                return name().toLowerCase(Locale.ROOT).replace('_', '-');
            }

            private static String canonical(String value) {
                return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            }
        }
    }

    /**
     * JWT resource-server settings of {@code oidc} mode. {@code issuerUri} (signing keys discovered from the
     * issuer's OpenID configuration on first use, {@code iss} validated) or {@code jwkSetUri} (keys fetched from
     * this URL) is required; when both are set the keys come from {@code jwkSetUri} and {@code iss} is still
     * validated against {@code issuerUri}. Tokens must be RS256-signed.
     *
     * @param issuerUri OIDC issuer, for example {@code https://idp.example.com/realms/sdlc}
     * @param jwkSetUri JWK set URL of the issuer
     * @param audience value the {@code aud} claim must contain (required in oidc mode)
     * @param rolesClaim claim holding the caller's roles: a top-level claim name (dots allowed, for namespaced
     *                   claims) or a dotted path into nested objects such as {@code realm_access.roles}; its value
     *                   is a list of strings or one space or comma separated string
     * @param identityClaim top-level claim used as the operator id ({@code sub}, often {@code preferred_username});
     *                      tokens without it are rejected
     * @param roleMapping claim values granting each control-plane role
     */
    public record Oidc(
            @Nullable String issuerUri,
            @Nullable String jwkSetUri,
            @Nullable String audience,
            @DefaultValue("roles") @NotBlank String rolesClaim,
            @DefaultValue("sub") @NotBlank String identityClaim,
            @DefaultValue @Valid @NotNull RoleMapping roleMapping) {
    }

    /**
     * Claim values granting the control-plane roles in {@code oidc} mode (each may list several values, comma
     * separated; matched exactly). Roles are independent: an admin is not implicitly an operator or approver.
     *
     * @param operator values granting OPERATOR (start runs and scenarios, clarify, re-plan, stop, resume)
     * @param approver values granting APPROVER (approve and reject gates)
     * @param admin values granting ADMIN (re-index the workspace)
     */
    public record RoleMapping(
            @DefaultValue("operator") @NotNull List<String> operator,
            @DefaultValue("approver") @NotNull List<String> approver,
            @DefaultValue("admin") @NotNull List<String> admin) {
    }

    /**
     * Operator console and its server-sent event streams.
     *
     * <p>The streams ({@code GET /api/v1/runs/{id}/events}, {@code GET /api/v1/runs/events}) carry the run changes
     * committed by <em>this</em> node. In a multi-node deployment a run executing on another node changes without an
     * event here; the console then polls that run (it compares the node in the {@code X-Sdlc-Node-Id} response header
     * of the stream with the run's {@code nodeId}) and keeps a slow safety poll for every open stream.</p>
     *
     * @param enabled whether the static console is served at {@code /}
     * @param maxSseConnections maximum concurrent event streams of this node; further stream requests are answered
     *                          with 503 (problem type stream-limit-reached)
     * @param sseHeartbeatInterval interval of the {@code heartbeat} event on every open stream
     * @param sseTimeout lifetime of one stream; the client reconnects afterwards (with {@code Last-Event-ID})
     */
    public record Console(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200") @Min(1) int maxSseConnections,
            @DefaultValue("15s") @NotNull Duration sseHeartbeatInterval,
            @DefaultValue("30m") @NotNull Duration sseTimeout) {

        /**
         * Validates the durations.
         */
        public Console {
            if (sseHeartbeatInterval != null && (sseHeartbeatInterval.isNegative() || sseHeartbeatInterval.isZero())) {
                throw new IllegalArgumentException("sdlc.console.sse-heartbeat-interval must be positive (got "
                        + sseHeartbeatInterval + ")");
            }
            if (sseTimeout != null && (sseTimeout.isNegative() || sseTimeout.isZero())) {
                throw new IllegalArgumentException("sdlc.console.sse-timeout must be positive (got " + sseTimeout
                        + ")");
            }
        }
    }

    /**
     * Reliability metrics endpoint.
     *
     * @param cacheTtl how long {@code GET /api/v1/metrics/reliability} reuses its last result; the computation reads
     *                 every run of the store (zero disables the cache)
     */
    public record Metrics(@DefaultValue("5s") @NotNull Duration cacheTtl) {

        /**
         * Validates the duration.
         */
        public Metrics {
            if (cacheTtl != null && cacheTtl.isNegative()) {
                throw new IllegalArgumentException("sdlc.metrics.cache-ttl must not be negative (got " + cacheTtl
                        + ")");
            }
        }
    }

    /**
     * Policy as code.
     *
     * @param pack location of a policy pack (JSON, or YAML when the name ends with {@code .yaml} or {@code .yml}): a
     *             file system path, or {@code classpath:<resource>}; unset for the built-in rules only. An invalid
     *             pack fails startup with every problem listed
     */
    public record Policy(@Nullable String pack) {

        /**
         * Returns whether a pack is configured.
         *
         * @return true when {@code pack} is set and not blank
         */
        public boolean hasPack() {
            return pack != null && !pack.isBlank();
        }
    }

    /**
     * Evaluation endpoints.
     *
     * @param enabled whether {@code POST /api/v1/evaluation/suite/run} may run the bundled evaluation suite (on an
     *                isolated in-memory engine with the deterministic agents); the per-run evaluation endpoint is
     *                always available. The local profile enables it, the prod profile disables it
     */
    public record Evaluation(@DefaultValue("false") boolean enabled) {
    }

    /**
     * Agent provider selection.
     *
     * @param defaultProvider provider used when a run request names none ({@code deterministic}, {@code anthropic}
     *                        or {@code ollama})
     */
    public record Agents(@DefaultValue("deterministic") @NotBlank String defaultProvider) {
    }

    /**
     * Governance rules of the control plane.
     *
     * @param fourEyes when true, the release-readiness gate cannot be approved by the operator who started the run
     *                 or by the operator who approved the design (separation of duties)
     * @param signingKey secret key that signs (HMAC-SHA256) the release change record when a run completes:
     *                   {@code base64:<value>} or raw text, decoding to at least
     *                   {@value HmacChangeRecordSigner#MIN_KEY_BYTES} bytes; unset or blank leaves change records
     *                   unsigned. Never logged
     * @param signingKeyId identifier of the signing key recorded with every signature (key rotation); it must not
     *                     reveal the key
     */
    public record Governance(
            @DefaultValue("false") boolean fourEyes,
            @Nullable String signingKey,
            @DefaultValue("local") @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}",
                    message = "must be 1 to 64 letters, digits or . _ : -") String signingKeyId) {

        /** Prefix of a base64-encoded {@code signingKey}. */
        public static final String BASE64_PREFIX = "base64:";

        /**
         * Returns whether a signing key is configured.
         *
         * @return true when {@code sdlc.governance.signing-key} is set and not blank
         */
        public boolean hasSigningKey() {
            return signingKey != null && !signingKey.isBlank();
        }

        /**
         * Decodes the signing key: the bytes of {@code base64:<value>} or the UTF-8 bytes of raw text (surrounding
         * whitespace removed). The caller should clear the returned array after use.
         *
         * @return the key bytes (at least {@value HmacChangeRecordSigner#MIN_KEY_BYTES})
         * @throws IllegalStateException when no key is set, the base64 is malformed or the key is too short; the
         *                               message never contains key material
         */
        public byte[] signingKeyBytes() {
            if (!hasSigningKey()) {
                throw new IllegalStateException("sdlc.governance.signing-key is not set");
            }
            String value = signingKey.strip();
            byte[] key;
            if (value.regionMatches(true, 0, BASE64_PREFIX, 0, BASE64_PREFIX.length())) {
                String encoded = value.substring(BASE64_PREFIX.length()).strip();
                try {
                    key = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException standard) {
                    try {
                        key = Base64.getUrlDecoder().decode(encoded);
                    } catch (IllegalArgumentException urlSafe) {
                        throw new IllegalStateException("sdlc.governance.signing-key starts with '" + BASE64_PREFIX
                                + "' but the rest is not valid base64");
                    }
                }
            } else {
                key = value.getBytes(StandardCharsets.UTF_8);
            }
            if (key.length < HmacChangeRecordSigner.MIN_KEY_BYTES) {
                int length = key.length;
                Arrays.fill(key, (byte) 0);
                throw new IllegalStateException("sdlc.governance.signing-key must decode to at least "
                        + HmacChangeRecordSigner.MIN_KEY_BYTES + " bytes (got " + length + "); generate one with "
                        + "'openssl rand -base64 32' and set it as " + BASE64_PREFIX + "<value>");
            }
            return key;
        }

        /**
         * Returns what is wrong with the configured signing key, if anything.
         *
         * @return the problem (without key material), or null when no key is set or the key is usable
         */
        public @Nullable String signingKeyProblem() {
            if (!hasSigningKey()) {
                return null;
            }
            try {
                Arrays.fill(signingKeyBytes(), (byte) 0);
                return null;
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
        }

        /**
         * Describes the settings without the signing key.
         *
         * @return a description safe to log
         */
        @Override
        public String toString() {
            return "Governance[fourEyes=" + fourEyes + ", signingKey=" + (hasSigningKey() ? "<redacted>" : "<none>")
                    + ", signingKeyId=" + signingKeyId + "]";
        }
    }

}
