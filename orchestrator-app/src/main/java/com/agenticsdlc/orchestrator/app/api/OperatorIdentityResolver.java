package com.agenticsdlc.orchestrator.app.api;

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
 */
@Component
public class OperatorIdentityResolver implements HandlerMethodArgumentResolver {

    /** Header carrying the operator id. */
    public static final String HEADER = "X-Operator-Id";

    /** Allowed operator ids. */
    public static final String PATTERN = "[A-Za-z0-9._@-]{1,64}";

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
        if (!VALID.matcher(id).matches()) {
            throw new OperatorIdentityException("operator identity invalid: " + HEADER + " must match " + PATTERN);
        }
        return Actor.human(id);
    }
}
