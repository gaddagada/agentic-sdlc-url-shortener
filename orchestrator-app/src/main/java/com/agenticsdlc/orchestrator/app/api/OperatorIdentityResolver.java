package com.agenticsdlc.orchestrator.app.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.agenticsdlc.orchestrator.core.audit.Actor;

/**
 * Resolves {@link OperatorId @OperatorId} {@link Actor} parameters: the human operator who issues a governance
 * command, recorded with every governance action in the audit trail.
 *
 * <ul>
 *   <li>{@code oidc} mode: the operator id is the identity claim of the verified bearer token (supplied by the
 *       {@link OperatorIdentitySource}). The {@code X-Operator-Id} header is optional; when sent it must name the
 *       same operator (compared case-insensitively, like every operator id comparison of the control plane),
 *       otherwise the request is rejected with 400 {@link ProblemTypes#OPERATOR_IDENTITY_MISMATCH}.</li>
 *   <li>{@code open} and {@code api-key} mode: the identity is asserted by the required {@code X-Operator-Id}
 *       header (attribution, not authentication; ADR 0009).</li>
 * </ul>
 *
 * <p>In every mode the id must match {@value #PATTERN}, and ids the engine uses for its own actors ({@code engine},
 * {@code system}, {@code deterministic}, compared case-insensitively, and anything starting with
 * {@code fallback:}) are reserved and rejected with 400: a human operator must never be able to pose as the engine
 * or an agent in the audit trail.</p>
 */
@Component
public class OperatorIdentityResolver implements HandlerMethodArgumentResolver {

    /** Header carrying the operator id. */
    public static final String HEADER = "X-Operator-Id";

    /** Allowed operator ids. */
    public static final String PATTERN = "[A-Za-z0-9._@-]{1,64}";

    private static final Set<String> RESERVED_IDS = Set.of("engine", "system", "deterministic");
    private static final String RESERVED_PREFIX = "fallback:";

    private static final Pattern VALID = Pattern.compile(PATTERN);

    private final ObjectProvider<OperatorIdentitySource> identitySource;

    /**
     * Creates the resolver.
     *
     * @param identitySource source of verified identities (absent in web slices without the security layer:
     *                       header-asserted identities only)
     */
    public OperatorIdentityResolver(ObjectProvider<OperatorIdentitySource> identitySource) {
        this.identitySource = identitySource;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(OperatorId.class) && Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
                                 NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) {
        OperatorIdentitySource source = identitySource.getIfAvailable(() -> OperatorIdentitySource.HEADER_ONLY);
        return resolve(webRequest.getHeader(HEADER), source.verifiedOperatorId());
    }

    /**
     * Validates a header-asserted operator id and returns the human actor ({@code open} and {@code api-key} mode).
     *
     * @param header header value, or null when absent
     * @return the human actor
     * @throws OperatorIdentityException when the value is missing or malformed
     */
    public static Actor resolve(@Nullable String header) {
        return resolve(header, null);
    }

    /**
     * Returns the human actor of a request: the verified identity when there is one (the header, if sent, must
     * agree with it), else the header-asserted identity.
     *
     * @param header {@code X-Operator-Id} header value, or null when absent
     * @param verifiedId the identity proven by the request's credentials, or null when the mode proves none
     * @return the human actor
     * @throws OperatorIdentityException when the identity is missing, malformed or reserved
     * @throws OperatorIdentityMismatchException when the header disagrees with the verified identity
     */
    public static Actor resolve(@Nullable String header, @Nullable String verifiedId) {
        if (verifiedId == null) {
            if (header == null || header.isBlank()) {
                throw new OperatorIdentityException("operator identity required: send the " + HEADER + " header");
            }
            return Actor.human(validate(header.strip(), HEADER + " must match " + PATTERN));
        }
        String id = validate(verifiedId.strip(), "the identity claim of the token must match " + PATTERN);
        if (header != null && !header.isBlank() && !header.strip().equalsIgnoreCase(id)) {
            throw new OperatorIdentityMismatchException("operator identity mismatch: " + HEADER
                    + " does not match the authenticated operator '" + id + "'; omit the header or send that id");
        }
        return Actor.human(id);
    }

    private static String validate(String id, String patternMessage) {
        if (isReserved(id)) {
            throw new OperatorIdentityException("operator identity invalid: engine, system, deterministic and "
                    + "fallback:* are reserved for the engine and its agents");
        }
        if (!VALID.matcher(id).matches()) {
            throw new OperatorIdentityException("operator identity invalid: " + patternMessage);
        }
        return id;
    }

    /**
     * Returns whether an operator id is reserved for the engine or its agents.
     *
     * @param id a stripped operator id
     * @return true for {@code engine}, {@code system}, {@code deterministic} (any case) and {@code fallback:*}
     */
    public static boolean isReserved(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return RESERVED_IDS.contains(lower) || lower.startsWith(RESERVED_PREFIX);
    }
}
