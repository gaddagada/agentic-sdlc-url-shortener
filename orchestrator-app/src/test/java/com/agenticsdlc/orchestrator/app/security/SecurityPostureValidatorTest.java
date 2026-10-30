package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

/**
 * {@link SecurityPostureValidator} in a minimal context: every production violation fails startup with a clear
 * message, a usable production posture starts, and outside {@code prod} an open API only logs a warning.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecurityPostureValidatorTest {

    private static final String LONG_KEY = "k".repeat(SecurityPostureValidator.MIN_PROD_API_KEY_LENGTH);
    private static final String SIGNING_KEY = "sdlc.governance.signing-key=base64:"
            + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidatorConfig.class);

    private final ApplicationContextRunner prod = runner
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"));

    @Test
    void prodRefusesTheOpenMode() {
        prod.withPropertyValues("sdlc.governance.four-eyes=true", "sdlc.console.enabled=false")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("refusing to start with the 'prod' profile")
                        .hasMessageContaining("sdlc.security.mode is open")
                        .hasMessageNotContaining("console"));
    }

    @Test
    void prodRefusesAShortApiKey() {
        prod.withPropertyValues("sdlc.security.mode=api-key", "sdlc.security.api-key=" + "k".repeat(31),
                        "sdlc.governance.four-eyes=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("sdlc.security.api-key is shorter than 32 characters")
                        .hasMessageNotContaining("kkkk"));
    }

    @Test
    void prodRefusesTheFourEyesRuleOff() {
        prod.withPropertyValues("sdlc.security.api-key=" + LONG_KEY, "sdlc.governance.four-eyes=false")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("sdlc.governance.four-eyes is false"));
    }

    @Test
    void prodRefusesTheConsoleWithoutAuthentication() {
        prod.withPropertyValues("sdlc.security.mode=open", "sdlc.console.enabled=true",
                        "sdlc.governance.four-eyes=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("the operator console is enabled without authentication"));
    }

    @Test
    void prodListsEveryViolationAtOnce() {
        prod.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("sdlc.security.mode is open")
                .hasMessageContaining("four-eyes")
                .hasMessageContaining("console")
                .hasMessageContaining("sdlc.governance.signing-key is not set"));
    }

    @Test
    void prodRequiresAUsableChangeRecordSigningKey() {
        prod.withPropertyValues("sdlc.security.api-key=" + LONG_KEY, "sdlc.governance.four-eyes=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("refusing to start with the 'prod' profile")
                        .hasMessageContaining("sdlc.governance.signing-key is not set, so release change records "
                                + "are not signed"));
        prod.withPropertyValues("sdlc.security.api-key=" + LONG_KEY, "sdlc.governance.four-eyes=true",
                        "sdlc.governance.signing-key=short-signing-secret")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("sdlc.governance.signing-key must decode to at least 32 bytes (got 20)")
                        .hasMessageNotContaining("short-signing-secret"));
    }

    @Test
    void prodStartsWithAnAuthenticatedApiFourEyesAndASigningKey() {
        prod.withPropertyValues("sdlc.security.api-key=" + LONG_KEY, "sdlc.governance.four-eyes=true", SIGNING_KEY)
                .run(context -> assertThat(context).hasNotFailed());
        prod.withPropertyValues("sdlc.security.mode=oidc", "sdlc.security.oidc.issuer-uri=https://idp.test",
                        "sdlc.security.oidc.audience=sdlc", "sdlc.governance.four-eyes=true", SIGNING_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodWarnsAboutSingleNodeLeasesTheEvaluationSuiteAndKeysFetchedOverPlainHttp(CapturedOutput output) {
        prod.withPropertyValues("sdlc.security.mode=oidc",
                        "sdlc.security.oidc.issuer-uri=http://idp.internal/realms/sdlc",
                        "sdlc.security.oidc.jwk-set-uri=HTTP://idp.internal/realms/sdlc/certs",
                        "sdlc.security.oidc.audience=sdlc", "sdlc.governance.four-eyes=true", SIGNING_KEY,
                        "sdlc.engine.lease-provider=memory", "sdlc.evaluation.enabled=true")
                .run(context -> assertThat(context).as("warnings, not refusals").hasNotFailed());

        assertThat(output.getOut().lines().filter(line -> line.contains(" WARN ")).toList()).anySatisfy(line ->
                        assertThat(line).contains("sdlc.engine.lease-provider=memory").contains("single-node only"))
                .anySatisfy(line -> assertThat(line).contains("sdlc.evaluation.enabled=true"))
                .anySatisfy(line -> assertThat(line).contains("sdlc.security.oidc.issuer-uri")
                        .contains("http://idp.internal/realms/sdlc"))
                .anySatisfy(line -> assertThat(line).contains("sdlc.security.oidc.jwk-set-uri"));
    }

    @Test
    void prodWithSharedLeasesNoSuiteAndHttpsKeysHasNoPostureWarning(CapturedOutput output) {
        prod.withPropertyValues("sdlc.security.mode=oidc", "sdlc.security.oidc.issuer-uri=https://idp.test",
                        "sdlc.security.oidc.jwk-set-uri=https://idp.test/certs", "sdlc.security.oidc.audience=sdlc",
                        "sdlc.governance.four-eyes=true", SIGNING_KEY)
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain("single-node only").doesNotContain("sdlc.evaluation.enabled=true")
                .doesNotContain("is not https");
    }

    @Test
    void outsideProdTheProductionWarningsAreNotLogged(CapturedOutput output) {
        runner.withPropertyValues("sdlc.engine.lease-provider=memory", "sdlc.evaluation.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain("single-node only").doesNotContain("sdlc.evaluation.enabled=true");
    }

    @Test
    void outsideProdNoSigningKeyIsRequired() {
        runner.withPropertyValues("sdlc.security.api-key=" + LONG_KEY).run(context -> assertThat(context)
                .hasNotFailed());
    }

    @Test
    void outsideProdTheOpenModeStartsWithAWarning(CapturedOutput output) {
        runner.run(context -> assertThat(context).hasNotFailed());

        assertThat(output).contains("API authentication disabled: local development mode");
    }

    @Test
    void anUnusableModeFailsInEveryProfile() {
        runner.withPropertyValues("sdlc.security.mode=api-key")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("sdlc.security.mode=api-key requires sdlc.security.api-key"));
        runner.withPropertyValues("sdlc.security.mode=oidc", "sdlc.security.oidc.role-mapping.admin=")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("issuer-uri or sdlc.security.oidc.jwk-set-uri")
                        .hasMessageContaining("sdlc.security.oidc.audience")
                        .hasMessageContaining("role-mapping.admin"));
    }

    @Test
    void theModeDefaultsToApiKeyWhenOnlyAKeyIsSetAndAnExplicitModeWins() {
        OrchestratorProperties.Security inferred = security(null, "secret");
        OrchestratorProperties.Security explicit = security(OrchestratorProperties.Security.Mode.OPEN, "secret");

        assertThat(inferred.effectiveMode()).isEqualTo(OrchestratorProperties.Security.Mode.API_KEY);
        assertThat(inferred.modeInferredFromApiKey()).isTrue();
        assertThat(explicit.effectiveMode()).isEqualTo(OrchestratorProperties.Security.Mode.OPEN);
        assertThat(security(null, " ").effectiveMode()).isEqualTo(OrchestratorProperties.Security.Mode.OPEN);
        assertThat(OrchestratorProperties.Security.Mode.parse("api-key"))
                .isEqualTo(OrchestratorProperties.Security.Mode.API_KEY);
        assertThat(OrchestratorProperties.Security.Mode.parse("OIDC"))
                .isEqualTo(OrchestratorProperties.Security.Mode.OIDC);
        assertThat(inferred.toString()).doesNotContain("secret").contains("apiKey=<redacted>");
    }

    private static OrchestratorProperties.Security security(OrchestratorProperties.Security.Mode mode, String key) {
        return new OrchestratorProperties.Security(mode, key, true, new OrchestratorProperties.Oidc(null, null, null,
                "roles", "sub", new OrchestratorProperties.RoleMapping(List.of("operator"),
                List.of("approver"), List.of("admin"))));
    }

    /**
     * The validator and the bound properties.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrchestratorProperties.class)
    @Import(SecurityPostureValidator.class)
    static class ValidatorConfig {
    }
}
