package com.agenticsdlc.orchestrator.app.security;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

/**
 * Builds the JWT verification of {@code oidc} mode from {@code sdlc.security.oidc.*}:
 *
 * <ul>
 *   <li>signature: RS256 against the issuer's keys, fetched from {@code jwk-set-uri}, or discovered from
 *       {@code issuer-uri}'s OpenID configuration on the first request (startup does not need the identity
 *       provider to be reachable);</li>
 *   <li>claims: {@code exp}/{@code nbf} (Spring Security's default 60 s clock skew), {@code iss} when
 *       {@code issuer-uri} is set, {@code aud} must contain {@code audience}, and the identity claim must be present
 *       and not blank; a token failing any check is answered with 401 {@code invalid_token};</li>
 *   <li>authentication: the principal name is the identity claim (the operator id) and the authorities are the
 *       roles mapped by {@link OidcRoleMapper}.</li>
 * </ul>
 */
final class OidcTokens {

    private OidcTokens() {
    }

    /**
     * Returns the token validator: default timestamp checks, the issuer (when configured), the audience and the
     * presence of the identity claim.
     *
     * @param oidc OIDC settings (validated, see {@link SecurityPostureValidator#configurationProblems})
     * @return the validator
     */
    static OAuth2TokenValidator<Jwt> validator(OrchestratorProperties.Oidc oidc) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(hasText(oidc.issuerUri()) ? JwtValidators.createDefaultWithIssuer(oidc.issuerUri().strip())
                : JwtValidators.createDefault());
        validators.add(new JwtAudienceValidator(requireText(oidc.audience(), "sdlc.security.oidc.audience")));
        String identityClaim = oidc.identityClaim().strip();
        validators.add(new JwtClaimValidator<@Nullable Object>(identityClaim,
                value -> value != null && !value.toString().isBlank()));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * Returns the decoder: keys from {@code jwk-set-uri} when set, else discovered lazily from {@code issuer-uri}.
     *
     * @param oidc OIDC settings
     * @return the decoder
     * @throws IllegalStateException when the settings are incomplete
     */
    static JwtDecoder decoder(OrchestratorProperties.Oidc oidc) {
        List<String> problems = SecurityPostureValidator.oidcProblems(oidc);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("invalid security configuration: " + String.join("; ", problems));
        }
        OAuth2TokenValidator<Jwt> validator = validator(oidc);
        if (hasText(oidc.jwkSetUri())) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oidc.jwkSetUri().strip()).build();
            decoder.setJwtValidator(validator);
            return decoder;
        }
        String issuer = requireText(oidc.issuerUri(), "sdlc.security.oidc.issuer-uri");
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }

    /**
     * Returns the converter from a verified token to the authentication: principal name from the identity claim,
     * authorities from the roles claim.
     *
     * @param oidc OIDC settings
     * @return the converter
     */
    static JwtAuthenticationConverter authenticationConverter(OrchestratorProperties.Oidc oidc) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(oidc.identityClaim().strip());
        converter.setJwtGrantedAuthoritiesConverter(new OidcRoleMapper(oidc.rolesClaim(), oidc.roleMapping()));
        return converter;
    }

    static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(@Nullable String value, String property) {
        if (!hasText(value)) {
            throw new IllegalStateException(property + " is required in oidc mode");
        }
        return value.strip();
    }
}
