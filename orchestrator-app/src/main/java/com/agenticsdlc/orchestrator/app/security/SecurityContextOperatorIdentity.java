package com.agenticsdlc.orchestrator.app.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityException;
import com.agenticsdlc.orchestrator.app.api.OperatorIdentitySource;

/**
 * {@link OperatorIdentitySource} backed by the Spring Security context: in {@code oidc} mode the operator id is the
 * principal name of the verified JWT (the configured identity claim); in {@code open} and {@code api-key} mode there
 * is no verified identity and the {@code X-Operator-Id} header applies.
 */
final class SecurityContextOperatorIdentity implements OperatorIdentitySource {

    private final boolean tokenRequired;

    /**
     * Creates the source.
     *
     * @param tokenRequired true in {@code oidc} mode: a verified token must be present
     */
    SecurityContextOperatorIdentity(boolean tokenRequired) {
        this.tokenRequired = tokenRequired;
    }

    @Override
    public @Nullable String verifiedOperatorId() {
        if (!tokenRequired) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            String name = token.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        // unreachable through the filter chain (governance endpoints need a role only a token grants): fail closed
        throw new OperatorIdentityException("operator identity required: the request carries no verified token "
                + "identity");
    }
}
