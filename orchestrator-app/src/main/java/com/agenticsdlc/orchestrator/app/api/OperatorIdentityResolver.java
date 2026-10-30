package com.agenticsdlc.orchestrator.app.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.agenticsdlc.orchestrator.core.audit.Actor;

/**
 * Resolves {@link OperatorId @OperatorId} {@link Actor} parameters from the {@code X-Operator-Id} header: every
 * mutating request must name the human operator who issues it, and the id is recorded with every governance
 * action in the audit trail. The identity is asserted, not authenticated (prototype; the production path is
 * SSO/OIDC, see ADR 0009).
 *
 * <p>Ids the engine uses for its own actors ({@code engine}, {@code system}, {@code deterministic}, compared
 * case-insensitively, and anything starting with {@code fallback:}) are reserved and rejected with 400: a human
 * operator must never be able to pose as the engine or an agent in the audit trail.</p>
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

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(OperatorId.class) && Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
                                 NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) {
        return resolve(webRequest.getHeader(HEADER));
    }

    /**
     * Validates a header value and returns the human actor.
     *
     * @param header header value, or null when absent
     * @return the human actor
     * @throws OperatorIdentityException when the value is missing or malformed
     */
    public static Actor resolve(@Nullable String header) {
        if (header == null || header.isBlank()) {
            throw new OperatorIdentityException("operator identity required: send the " + HEADER + " header");
        }
        String id = header.strip();
        if (isReserved(id)) {
            throw new OperatorIdentityException("operator identity invalid: engine, system, deterministic and "
                    + "fallback:* are reserved for the engine and its agents");
        }
        if (!VALID.matcher(id).matches()) {
            throw new OperatorIdentityException("operator identity invalid: " + HEADER + " must match " + PATTERN);
        }
        return Actor.human(id);
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
