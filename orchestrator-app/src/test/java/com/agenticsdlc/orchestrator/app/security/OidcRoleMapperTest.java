package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

/**
 * {@link OidcRoleMapper}: claim lookup by name or dotted path, value shapes and the role mapping.
 */
class OidcRoleMapperTest {

    private static final OrchestratorProperties.RoleMapping DEFAULTS = new OrchestratorProperties.RoleMapping(
            List.of("operator"), List.of("approver"), List.of("admin"));

    @Test
    void aTopLevelListClaimIsMappedToRoleAuthorities() {
        OidcRoleMapper mapper = new OidcRoleMapper("roles", DEFAULTS);

        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of("approver", "operator", "unrelated"))))))
                .containsExactly("ROLE_OPERATOR", "ROLE_APPROVER");
        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of("admin")))))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void aDottedPathReadsNestedObjects() {
        OidcRoleMapper mapper = new OidcRoleMapper("realm_access.roles", DEFAULTS);
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("operator", "offline_access")),
                "resource_access", Map.of("account", Map.of("roles", List.of("admin"))));

        assertThat(names(mapper.convert(jwt(claims)))).containsExactly("ROLE_OPERATOR");
        assertThat(names(new OidcRoleMapper("resource_access.account.roles", DEFAULTS).convert(jwt(claims))))
                .containsExactly("ROLE_ADMIN");
        assertThat(names(new OidcRoleMapper("realm_access.missing.roles", DEFAULTS).convert(jwt(claims)))).isEmpty();
        assertThat(names(new OidcRoleMapper("realm_access.roles.deeper", DEFAULTS).convert(jwt(claims)))).isEmpty();
    }

    @Test
    void aClaimNameContainingDotsIsReadAsATopLevelClaimFirst() {
        OidcRoleMapper mapper = new OidcRoleMapper("https://sdlc.example.com/roles", DEFAULTS);

        assertThat(names(mapper.convert(jwt(Map.of("https://sdlc.example.com/roles", List.of("approver"))))))
                .containsExactly("ROLE_APPROVER");
    }

    @Test
    void aStringClaimIsSplitOnSpacesAndCommas() {
        OidcRoleMapper mapper = new OidcRoleMapper("groups", DEFAULTS);

        assertThat(names(mapper.convert(jwt(Map.of("groups", "operator  approver,admin")))))
                .containsExactly("ROLE_OPERATOR", "ROLE_APPROVER", "ROLE_ADMIN");
    }

    @Test
    void unsupportedShapesMissingClaimsAndCaseDifferencesGrantNothing() {
        OidcRoleMapper mapper = new OidcRoleMapper("roles", DEFAULTS);

        assertThat(names(mapper.convert(jwt(Map.of("roles", Map.of("operator", true)))))).isEmpty();
        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of(1, true, Map.of())))))).isEmpty();
        assertThat(names(mapper.convert(jwt(Map.of("other", List.of("operator")))))).isEmpty();
        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of("Operator", "ADMIN")))))).isEmpty();
    }

    @Test
    void aMappingMayNameSeveralClaimValuesPerRole() {
        OidcRoleMapper mapper = new OidcRoleMapper("roles", new OrchestratorProperties.RoleMapping(
                List.of("sdlc-operator", "sdlc-lead"), List.of("sdlc-lead"), List.of(" sdlc-admin ")));

        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of("sdlc-lead"))))))
                .containsExactly("ROLE_OPERATOR", "ROLE_APPROVER");
        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of("sdlc-admin")))))).containsExactly("ROLE_ADMIN");
        assertThat(names(mapper.convert(jwt(Map.of("roles", List.of("operator")))))).as("defaults replaced").isEmpty();
    }

    @Test
    void claimValuesAreStrippedAndBlanksDropped() {
        assertThat(OidcRoleMapper.claimValues(Map.of("r", List.of(" a ", "", "b")), "r")).containsExactly("a", "b");
        assertThat(OidcRoleMapper.claimValues(Map.of("r", " , "), "r")).isEmpty();
        assertThat(OidcRoleMapper.claimValues(Map.of(), "r")).isEmpty();
    }

    private static List<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user").issuedAt(now)
                .expiresAt(now.plusSeconds(300)).claims(c -> c.putAll(claims)).build();
    }
}
