package com.agenticsdlc.orchestrator.app.api;

import org.jspecify.annotations.Nullable;

/**
 * Supplies the operator identity proven by the credentials of the current request, when the security mode proves
 * one. Implemented by the security layer so that the API layer does not depend on Spring Security:
 *
 * <ul>
 *   <li>{@code oidc} mode: the identity claim of the verified bearer token; a request without a verified token
 *       never reaches a governance endpoint, and the implementation fails closed if one does;</li>
 *   <li>{@code open} and {@code api-key} mode: no verified identity ({@code null}); the {@code X-Operator-Id}
 *       header is the asserted identity.</li>
 * </ul>
 */
@FunctionalInterface
public interface OperatorIdentitySource {

    /** A source that never proves an identity (header-asserted identities only). */
    OperatorIdentitySource HEADER_ONLY = () -> null;

    /**
     * Returns the verified operator id of the current request.
     *
     * @return the verified id, or null when identities are asserted by header in this security mode
     * @throws OperatorIdentityException when the mode requires a verified identity and the request has none
     */
    @Nullable String verifiedOperatorId();
}
