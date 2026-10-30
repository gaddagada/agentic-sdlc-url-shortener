package com.agenticsdlc.orchestrator.app.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.agenticsdlc.orchestrator.app.api.ProblemTypes;

import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the authentication (401) and authorization (403) failures of the Spring Security filter chain as RFC 9457
 * Problem Details, consistent with {@code ApiExceptionHandler}: {@code type} {@link ProblemTypes#UNAUTHORIZED} or
 * {@link ProblemTypes#FORBIDDEN}, {@code title}, {@code status}, {@code detail} and {@code instance}.
 *
 * <p>With bearer tokens ({@code oidc} mode) the RFC 6750 {@code WWW-Authenticate: Bearer ...} challenge is set by
 * Spring Security's {@link BearerTokenAuthenticationEntryPoint} and {@link BearerTokenAccessDeniedHandler}
 * ({@code error="invalid_token"} for a rejected token, {@code error="insufficient_scope"} for a missing role);
 * otherwise the challenge names the {@code X-Api-Key} header. A 403 names the role(s) the operation needs, from
 * {@link ApiAuthorizationRules}. Token contents are never echoed beyond the validation error description.</p>
 */
final class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** Realm announced in {@code WWW-Authenticate} challenges. */
    static final String REALM = "sdlc-orchestrator";

    private final JsonMapper mapper;
    private final boolean bearer;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerDeniedHandler = new BearerTokenAccessDeniedHandler();

    /**
     * Creates the handlers.
     *
     * @param mapper JSON mapper used to write the Problem Details
     * @param bearer whether callers authenticate with bearer tokens ({@code oidc} mode)
     */
    SecurityProblemHandlers(JsonMapper mapper, boolean bearer) {
        this.mapper = mapper;
        this.bearer = bearer;
        bearerEntryPoint.setRealmName(REALM);
        bearerDeniedHandler.setRealmName(REALM);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        String detail;
        if (bearer) {
            bearerEntryPoint.commence(request, response, exception);
            String reason = exception instanceof OAuth2AuthenticationException oauth
                    ? oauth.getError().getDescription() : null;
            detail = reason == null || reason.isBlank()
                    ? "A bearer token is required: send Authorization: Bearer <access token>."
                    : "The bearer token was rejected: " + reason;
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, ApiKeyAuthFilter.CHALLENGE);
            detail = "Authentication is required for this request.";
        }
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, ProblemTypes.UNAUTHORIZED.toString(),
                "Unauthorized", detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        if (bearer) {
            bearerDeniedHandler.handle(request, response, exception);
        }
        List<ControlPlaneRole> roles = ApiAuthorizationRules.allowedRoles(request.getMethod(), path(request));
        String needed = roles.size() == 1 ? "the " + roles.getFirst() + " role"
                : "one of the roles " + roles.stream().map(Enum::name).collect(Collectors.joining(", "));
        write(request, response, HttpServletResponse.SC_FORBIDDEN, ProblemTypes.FORBIDDEN.toString(), "Forbidden",
                request.getMethod() + " " + path(request) + " requires " + needed
                        + "; the authenticated caller does not hold it.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String type,
                       String title, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(problem));
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "/" : request.getRequestURI();
        @Nullable String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length())
                : uri;
    }
}
