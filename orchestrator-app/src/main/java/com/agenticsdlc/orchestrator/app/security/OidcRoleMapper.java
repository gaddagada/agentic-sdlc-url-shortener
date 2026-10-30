package com.agenticsdlc.orchestrator.app.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

/**
 * Maps the roles claim of a verified JWT to the control-plane role authorities ({@code ROLE_OPERATOR},
 * {@code ROLE_APPROVER}, {@code ROLE_ADMIN}) using {@code sdlc.security.oidc.role-mapping}. Claim values that map to
 * no role are ignored; scopes are not mapped (authorization is role based only).
 *
 * <p>The claim is located by {@code sdlc.security.oidc.roles-claim}: first as a top-level claim with exactly that
 * name (so namespaced claims such as {@code https://sdlc.example.com/roles} work), otherwise as a dotted path into
 * nested JSON objects (Keycloak's {@code realm_access.roles}). The value may be a JSON array of strings or a
 * single string of space or comma separated values; any other shape grants nothing. Values are matched exactly
 * (case-sensitive) against the mapping.</p>
 */
final class OidcRoleMapper implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String rolesClaim;
    private final OrchestratorProperties.RoleMapping mapping;

    /**
     * Creates the mapper.
     *
     * @param rolesClaim the roles claim name or dotted path
     * @param mapping claim values per role
     */
    OidcRoleMapper(String rolesClaim, OrchestratorProperties.RoleMapping mapping) {
        this.rolesClaim = rolesClaim.strip();
        this.mapping = mapping;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return authorities(claimValues(jwt.getClaims(), rolesClaim));
    }

    /**
     * Returns the authorities granted by a set of claim values.
     *
     * @param values claim values
     * @return role authorities in role order (OPERATOR, APPROVER, ADMIN), possibly empty
     */
    List<GrantedAuthority> authorities(Set<String> values) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        grant(authorities, values, mapping.operator(), ControlPlaneRole.OPERATOR);
        grant(authorities, values, mapping.approver(), ControlPlaneRole.APPROVER);
        grant(authorities, values, mapping.admin(), ControlPlaneRole.ADMIN);
        return List.copyOf(authorities);
    }

    private static void grant(List<GrantedAuthority> authorities, Set<String> values, List<String> mapped,
                              ControlPlaneRole role) {
        for (String value : mapped) {
            if (value != null && !value.isBlank() && values.contains(value.strip())) {
                authorities.add(new SimpleGrantedAuthority(role.authority()));
                return;
            }
        }
    }

    /**
     * Reads the values of a claim given by name or dotted path.
     *
     * @param claims the token claims
     * @param path a claim name, or a dotted path such as {@code realm_access.roles}
     * @return the claim values (strings, stripped, without blanks), empty when the claim is absent or not a string
     *         or list of strings
     */
    static Set<String> claimValues(Map<String, Object> claims, String path) {
        Object value = claims.containsKey(path) ? claims.get(path) : navigate(claims, path);
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof String text && !text.isBlank()) {
                    values.add(text.strip());
                }
            }
        } else if (value instanceof String text) {
            for (String part : text.split("[\\s,]+")) {
                if (!part.isBlank()) {
                    values.add(part);
                }
            }
        }
        return values;
    }

    private static @Nullable Object navigate(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
