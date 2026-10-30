package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

/**
 * {@link OidcTokens}: claim validation (audience, issuer, timestamps, identity claim), decoder construction and the
 * token-to-authentication conversion.
 */
class OidcTokensTest {

    private static final String ISSUER = "https://idp.test/realms/sdlc";
    private static final String AUDIENCE = "sdlc-orchestrator";

    @Test
    void aTokenForTheAudienceFromTheIssuerIsValid() {
        OAuth2TokenValidator<Jwt> validator = OidcTokens.validator(oidc(ISSUER, null, AUDIENCE, "sub"));

        assertThat(validator.validate(jwt(b -> b.audience(List.of(AUDIENCE)))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(b -> b.audience(List.of("account", AUDIENCE)))).hasErrors())
                .as("aud may list several audiences").isFalse();
    }

    @Test
    void theAudienceIsRequiredAndValidated() {
        OAuth2TokenValidator<Jwt> validator = OidcTokens.validator(oidc(ISSUER, null, AUDIENCE, "sub"));

        assertThat(errors(validator.validate(jwt(b -> b.audience(List.of("other-app")))))).contains("aud");
        assertThat(errors(validator.validate(jwt(b -> b.claims(c -> c.remove("aud")))))).contains("aud");
        assertThat(validator.validate(jwt(b -> b.audience(List.of("sdlc-orchestrator-admin")))).hasErrors())
                .as("no prefix match").isTrue();
    }

    @Test
    void theIssuerIsValidatedWhenConfigured() {
        OAuth2TokenValidator<Jwt> withIssuer = OidcTokens.validator(oidc(ISSUER, "https://idp.test/jwks", AUDIENCE,
                "sub"));
        OAuth2TokenValidator<Jwt> withoutIssuer = OidcTokens.validator(oidc(null, "https://idp.test/jwks", AUDIENCE,
                "sub"));

        assertThat(errors(withIssuer.validate(jwt(b -> b.issuer("https://evil.test"))))).contains("iss");
        assertThat(withoutIssuer.validate(jwt(b -> b.issuer("https://elsewhere.test"))).hasErrors()).isFalse();
    }

    @Test
    void expiredTokensAreInvalid() {
        OAuth2TokenValidator<Jwt> validator = OidcTokens.validator(oidc(ISSUER, null, AUDIENCE, "sub"));
        Instant past = Instant.now().minusSeconds(3600);

        assertThat(validator.validate(jwt(b -> b.issuedAt(past.minusSeconds(60)).expiresAt(past))).hasErrors())
                .isTrue();
    }

    @Test
    void theIdentityClaimMustBePresentAndNotBlank() {
        OAuth2TokenValidator<Jwt> validator = OidcTokens.validator(oidc(ISSUER, null, AUDIENCE, "preferred_username"));

        assertThat(validator.validate(jwt(b -> b.claim("preferred_username", "alice"))).hasErrors()).isFalse();
        assertThat(errors(validator.validate(jwt(b -> { })))).contains("preferred_username");
        assertThat(validator.validate(jwt(b -> b.claim("preferred_username", " "))).hasErrors()).isTrue();
    }

    @Test
    void theConverterTakesThePrincipalFromTheIdentityClaimAndRolesFromTheRolesClaim() {
        OrchestratorProperties.Oidc oidc = new OrchestratorProperties.Oidc(ISSUER, null, AUDIENCE,
                "realm_access.roles", "preferred_username", new OrchestratorProperties.RoleMapping(
                List.of("operator"), List.of("approver"), List.of("admin")));

        JwtAuthenticationToken authentication = (JwtAuthenticationToken) OidcTokens.authenticationConverter(oidc)
                .convert(jwt(b -> b.claim("preferred_username", "alice")
                        .claim("realm_access", Map.of("roles", List.of("approver")))));

        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getAuthorities()).extracting(a -> a.getAuthority()).contains("ROLE_APPROVER")
                .doesNotContain("ROLE_OPERATOR", "ROLE_ADMIN");
    }

    @Test
    void anIssuerOnlyDecoderDiscoversItsKeysLazily() {
        // building it must not contact the (unreachable) issuer: startup never depends on the identity provider
        JwtDecoder decoder = OidcTokens.decoder(oidc("http://127.0.0.1:9/realms/none", null, AUDIENCE, "sub"));

        assertThat(decoder).isInstanceOf(SupplierJwtDecoder.class);
    }

    @Test
    void incompleteSettingsAreRefusedWithTheMissingProperties() {
        assertThatThrownBy(() -> OidcTokens.decoder(oidc(ISSUER, null, " ", "sub")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sdlc.security.oidc.audience");
        assertThatThrownBy(() -> OidcTokens.decoder(oidc(null, null, AUDIENCE, "sub")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sdlc.security.oidc.issuer-uri")
                .hasMessageContaining("sdlc.security.oidc.jwk-set-uri");
    }

    private static String errors(OAuth2TokenValidatorResult result) {
        return String.join(" | ", result.getErrors().stream().map(OAuth2Error::getDescription).toList());
    }

    private static OrchestratorProperties.Oidc oidc(String issuer, String jwkSetUri, String audience,
                                                    String identityClaim) {
        return new OrchestratorProperties.Oidc(issuer, jwkSetUri, audience, "roles", identityClaim,
                new OrchestratorProperties.RoleMapping(List.of("operator"), List.of("approver"), List.of("admin")));
    }

    private static Jwt jwt(Consumer<Jwt.Builder> customizer) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").header("typ", "JWT")
                .issuer(ISSUER).subject("user-1").audience(List.of(AUDIENCE)).issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        customizer.accept(builder);
        return builder.build();
    }
}
