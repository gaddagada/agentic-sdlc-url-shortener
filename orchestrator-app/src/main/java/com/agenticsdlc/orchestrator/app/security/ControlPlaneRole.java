package com.agenticsdlc.orchestrator.app.security;

import java.util.Arrays;
import java.util.List;

/**
 * The roles of the control plane. In {@code oidc} mode they are granted by claim values of the bearer token (see
 * {@code sdlc.security.oidc.role-mapping}); in {@code open} and {@code api-key} mode every caller holds all three,
 * so existing clients keep working. Roles are independent: ADMIN does not imply OPERATOR or APPROVER.
 */
public enum ControlPlaneRole {

    /** Starts runs and scenarios, answers clarifications, re-plans, stops and resumes runs. */
    OPERATOR,
    /** Approves and rejects stage gates. */
    APPROVER,
    /** Re-indexes the workspace. */
    ADMIN;

    /** Prefix of role authorities. */
    public static final String AUTHORITY_PREFIX = "ROLE_";

    /**
     * Returns the Spring Security authority of the role.
     *
     * @return {@code ROLE_OPERATOR}, {@code ROLE_APPROVER} or {@code ROLE_ADMIN}
     */
    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    /**
     * Returns the authorities of every role (what {@code open} and {@code api-key} callers hold).
     *
     * @return all role authorities
     */
    public static List<String> allAuthorities() {
        return Arrays.stream(values()).map(ControlPlaneRole::authority).toList();
    }

    /**
     * Returns the names of every role (for {@code hasAnyRole}).
     *
     * @return all role names
     */
    static String[] allNames() {
        return Arrays.stream(values()).map(Enum::name).toArray(String[]::new);
    }
}
