package com.agenticsdlc.orchestrator.app.security;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties.Security.Mode;

/**
 * Checks the security posture when the context starts and refuses to start an unsafe configuration.
 *
 * <ul>
 *   <li>In every profile: the selected mode must be usable ({@code api-key} needs a key; {@code oidc} needs an
 *       audience, an issuer URI or JWK set URI and a non-empty value for every role mapping).</li>
 *   <li>With the {@value #PROD_PROFILE} profile active, startup fails when (a) the mode is {@code open}, (b) the
 *       mode is {@code api-key} with a key shorter than {@value #MIN_PROD_API_KEY_LENGTH} characters, (c) the
 *       four-eyes rule is disabled, (d) the operator console is enabled while the API is unauthenticated (anyone
 *       reaching the console could start, approve and stop runs), or (e) no usable change record signing key is
 *       configured ({@code sdlc.governance.signing-key}, at least 32 bytes). Every violation is listed in one
 *       message.</li>
 *   <li>Outside {@value #PROD_PROFILE}, {@code open} mode logs a single WARN that the API is unauthenticated (local
 *       development mode).</li>
 * </ul>
 *
 * <p>The mode in force is logged once at startup; secrets (the API key, the signing key) are never logged.</p>
 */
@Component
public class SecurityPostureValidator implements InitializingBean {

    /** The production profile. */
    public static final String PROD_PROFILE = "prod";

    /** Minimum length of the API key under the production profile. */
    public static final int MIN_PROD_API_KEY_LENGTH = 32;

    private static final Logger LOG = LoggerFactory.getLogger(SecurityPostureValidator.class);

    private final OrchestratorProperties properties;
    private final Environment environment;

    /**
     * Creates the validator.
     *
     * @param properties configuration
     * @param environment environment (active profiles)
     */
    public SecurityPostureValidator(OrchestratorProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        OrchestratorProperties.Security security = properties.security();
        requireUsable(security);
        boolean production = environment.matchesProfiles(PROD_PROFILE);
        if (production) {
            List<String> violations = productionViolations(properties);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("refusing to start with the '" + PROD_PROFILE + "' profile: "
                        + String.join("; ", violations));
            }
        }
        logPosture(security, production);
    }

    /**
     * Fails when the selected security mode cannot work (called by every bean that needs a usable mode, so the
     * first one created reports the problem).
     *
     * @param security security settings
     * @throws IllegalStateException listing every problem
     */
    static void requireUsable(OrchestratorProperties.Security security) {
        List<String> problems = configurationProblems(security);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("invalid security configuration: " + String.join("; ", problems));
        }
    }

    /**
     * Returns what keeps the selected security mode from working, in any profile.
     *
     * @param security security settings
     * @return problems, empty when the mode is usable
     */
    public static List<String> configurationProblems(OrchestratorProperties.Security security) {
        List<String> problems = new ArrayList<>();
        Mode mode = security.effectiveMode();
        if (mode == Mode.API_KEY && !security.hasApiKey()) {
            problems.add("sdlc.security.mode=api-key requires sdlc.security.api-key");
        }
        if (mode == Mode.OIDC) {
            problems.addAll(oidcProblems(security.oidc()));
        }
        return List.copyOf(problems);
    }

    /**
     * Returns what is missing from the OIDC settings.
     *
     * @param oidc OIDC settings
     * @return problems, empty when complete
     */
    static List<String> oidcProblems(OrchestratorProperties.Oidc oidc) {
        List<String> problems = new ArrayList<>();
        if (!OidcTokens.hasText(oidc.issuerUri()) && !OidcTokens.hasText(oidc.jwkSetUri())) {
            problems.add("sdlc.security.mode=oidc requires sdlc.security.oidc.issuer-uri or "
                    + "sdlc.security.oidc.jwk-set-uri");
        }
        if (!OidcTokens.hasText(oidc.audience())) {
            problems.add("sdlc.security.mode=oidc requires sdlc.security.oidc.audience (the aud value tokens "
                    + "must carry)");
        }
        OrchestratorProperties.RoleMapping mapping = oidc.roleMapping();
        requireMapping(problems, "operator", mapping.operator());
        requireMapping(problems, "approver", mapping.approver());
        requireMapping(problems, "admin", mapping.admin());
        return problems;
    }

    /**
     * Returns the violations of the production posture.
     *
     * @param properties configuration
     * @return violations, empty when the configuration may run in production
     */
    public static List<String> productionViolations(OrchestratorProperties properties) {
        OrchestratorProperties.Security security = properties.security();
        Mode mode = security.effectiveMode();
        List<String> violations = new ArrayList<>();
        if (mode == Mode.OPEN) {
            violations.add("sdlc.security.mode is open, so the API accepts unauthenticated requests (use oidc, or "
                    + "api-key with a key of at least " + MIN_PROD_API_KEY_LENGTH + " characters)");
        }
        if (mode == Mode.API_KEY && security.apiKey().strip().length() < MIN_PROD_API_KEY_LENGTH) {
            violations.add("sdlc.security.api-key is shorter than " + MIN_PROD_API_KEY_LENGTH + " characters");
        }
        if (!properties.governance().fourEyes()) {
            violations.add("sdlc.governance.four-eyes is false, so one operator could start a run and approve "
                    + "its release");
        }
        if (properties.console().enabled() && mode == Mode.OPEN) {
            violations.add("the operator console is enabled without authentication (anyone reaching it could "
                    + "start, approve and stop runs; enable api-key or oidc mode, or set sdlc.console.enabled=false)");
        }
        OrchestratorProperties.Governance governance = properties.governance();
        if (!governance.hasSigningKey()) {
            violations.add("sdlc.governance.signing-key is not set, so release change records are not signed (set a "
                    + "key of at least 32 bytes, for example base64:<output of openssl rand -base64 32>)");
        } else {
            String problem = governance.signingKeyProblem();
            if (problem != null) {
                violations.add(problem);
            }
        }
        return List.copyOf(violations);
    }

    private static void requireMapping(List<String> problems, String role, List<String> values) {
        if (values.stream().noneMatch(OidcTokens::hasText)) {
            problems.add("sdlc.security.oidc.role-mapping." + role + " must name at least one claim value");
        }
    }

    private static void logPosture(OrchestratorProperties.Security security, boolean production) {
        Mode mode = security.effectiveMode();
        if (security.modeInferredFromApiKey()) {
            LOG.info("sdlc.security.mode is not set; using api-key because sdlc.security.api-key is set "
                    + "(backwards compatibility)");
        }
        if (mode != Mode.API_KEY && security.hasApiKey()) {
            LOG.warn("sdlc.security.api-key is set but ignored in {} mode", mode.propertyValue());
        }
        switch (mode) {
            case OPEN -> LOG.warn("API authentication disabled: local development mode, every caller holds every "
                    + "role (set sdlc.security.mode=api-key or oidc to enable authentication)");
            case API_KEY -> LOG.info("API authentication enabled: mode=api-key, /api/** requires the {} header",
                    ApiKeyAuthFilter.HEADER);
            case OIDC -> LOG.info("API authentication enabled: mode=oidc issuer={} jwkSetUri={} audience={} "
                            + "rolesClaim={} identityClaim={}", security.oidc().issuerUri(), security.oidc().jwkSetUri(),
                    security.oidc().audience(), security.oidc().rolesClaim(), security.oidc().identityClaim());
        }
        LOG.info("security posture: mode={} publicDocs={} prodProfile={}", mode.propertyValue(),
                security.publicDocs(), production);
    }
}
