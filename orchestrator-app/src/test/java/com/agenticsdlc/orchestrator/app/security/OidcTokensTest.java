package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link OidcTokens}: claim validation (audience, issuer, timestamps, identity claim), decoder construction (only
 * RS256 signatures, whichever way the keys are found) and the token-to-authentication conversion.
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
    void aTokenWithoutAnExpiryIsInvalid() {
        // Spring Security's default timestamp check accepts a token without exp; such a token would never expire
        OAuth2TokenValidator<Jwt> validator = OidcTokens.validator(oidc(ISSUER, null, AUDIENCE, "sub"));

        assertThat(errors(validator.validate(jwt(b -> b.claims(c -> c.remove("exp")))))).contains("exp");
    }

    @Test
    void theIdentityClaimMustBeAString() {
        OAuth2TokenValidator<Jwt> validator = OidcTokens.validator(oidc(ISSUER, null, AUDIENCE, "preferred_username"));

        assertThat(validator.validate(jwt(b -> b.claim("preferred_username", List.of("alice")))).hasErrors())
                .as("a list is not an operator id").isTrue();
        assertThat(validator.validate(jwt(b -> b.claim("preferred_username", 42))).hasErrors())
                .as("a number is not an operator id").isTrue();
        assertThat(validator.validate(jwt(b -> b.claim("preferred_username", Map.of("name", "alice")))).hasErrors())
                .as("an object is not an operator id").isTrue();
    }

    @Test
    void onlyRs256SignaturesAreAcceptedWhetherTheKeysComeFromTheIssuerOrAJwkSetUri() throws Exception {
        RSAKey key = rsaKey();
        HttpServer idp = identityProvider(new JWKSet(key.toPublicJWK()).toString());
        try {
            String issuer = "http://127.0.0.1:" + idp.getAddress().getPort() + "/realms/sdlc";
            JwtDecoder discovered = OidcTokens.decoder(oidc(issuer, null, AUDIENCE, "sub"));
            JwtDecoder fromJwkSetUri = OidcTokens.decoder(oidc(issuer, issuer + "/jwks", AUDIENCE, "sub"));
            for (JwtDecoder decoder : List.of(discovered, fromJwkSetUri)) {
                assertThat(decoder.decode(signed(key, JWSAlgorithm.RS256, issuer)).getSubject()).isEqualTo("alice");
                for (JWSAlgorithm other : List.of(JWSAlgorithm.RS384, JWSAlgorithm.RS512, JWSAlgorithm.PS256)) {
                    String token = signed(key, other, issuer);
                    assertThatThrownBy(() -> decoder.decode(token)).as("%s with %s", other, decoder)
                            .isInstanceOf(JwtException.class);
                }
            }
        } finally {
            idp.stop(0);
        }
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

    private static String signed(RSAKey key, JWSAlgorithm algorithm, String issuer) throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject("alice").audience(AUDIENCE)
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey rsaKey() throws JOSEException {
        // no "alg" member, like the keys of many identity providers: the key itself does not restrict the algorithm
        return new RSAKeyGenerator(2048).keyID("rotating-key").generate();
    }

    /** A loopback stub of an OpenID provider: its discovery document and its JWK set. */
    private static HttpServer identityProvider(String jwks) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/sdlc";
        serve(server, "/realms/sdlc/.well-known/openid-configuration",
                "{\"issuer\": \"" + issuer + "\", \"jwks_uri\": \"" + issuer + "/jwks\", "
                        + "\"subject_types_supported\": [\"public\"], "
                        + "\"id_token_signing_alg_values_supported\": [\"RS256\"]}");
        serve(server, "/realms/sdlc/jwks", jwks);
        server.start();
        return server;
    }

    private static void serve(HttpServer server, String path, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
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
