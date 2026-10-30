package com.agenticsdlc.orchestrator.app.security;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.DispatcherType;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The authorization rules of the control plane, enforced by the Spring Security filter chain in every security
 * mode (in {@code open} and {@code api-key} mode every caller holds every role, so they never deny there):
 *
 * <ul>
 *   <li>public: the static console ({@code /}, {@code /index.html}, {@code /app.js}, {@code /styles.css},
 *       {@code /js/**}), {@code /actuator/health/**}, the error page and, unless
 *       {@code sdlc.security.public-docs=false}, the OpenAPI document and Swagger UI. The console is static code
 *       without data (every datum comes from the protected API) and the OpenAPI document describes the contract,
 *       not data, so neither needs a token; liveness probes must work without credentials;</li>
 *   <li>OPERATOR: {@code POST /api/v1/runs}, {@code POST /api/v1/runs/{id}/clarify|replan|stop|resume},
 *       {@code POST /api/v1/scenarios/{key}/run};</li>
 *   <li>APPROVER: {@code POST /api/v1/runs/{id}/stages/{stageId}/approve|reject};</li>
 *   <li>ADMIN: {@code POST /api/v1/workspace/reindex}, {@code POST /api/v1/evaluation/suite/run}, and any other
 *       non-GET {@code /api/**} request (fail closed for endpoints added without a rule);</li>
 *   <li>any role: every {@code GET /api/**} (including the server-sent event streams, the policy and the run
 *       evaluation) and every other path (other actuator endpoints, documents when not public).</li>
 * </ul>
 *
 * <p>Async dispatches (the completion of a server-sent event stream) are permitted: they only continue a request
 * whose original dispatch was already authorized by these rules.</p>
 *
 * <p>Paths are matched with {@link PathPattern}s on the decoded path; requests whose path is not normalized
 * ({@code ;} parameters, {@code //}, dot segments, encoded slashes) are rejected by Spring Security's
 * {@code StrictHttpFirewall} before any rule is evaluated.</p>
 */
public final class ApiAuthorizationRules {

    /** Static console resources (always public). */
    public static final List<String> CONSOLE_PATHS = List.of("/", "/index.html", "/app.js", "/styles.css", "/js/**",
            "/favicon.ico");

    /** Health endpoint paths (always public). */
    public static final List<String> HEALTH_PATHS = List.of("/actuator/health", "/actuator/health/**");

    /** OpenAPI document and Swagger UI paths (public unless {@code sdlc.security.public-docs=false}). */
    public static final List<String> DOCS_PATHS = List.of("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui.html", "/swagger-ui/**");

    /** The governance commands and the role each requires. */
    public static final List<Rule> COMMAND_RULES = List.of(
            new Rule(HttpMethod.POST, "/api/v1/runs", ControlPlaneRole.OPERATOR),
            new Rule(HttpMethod.POST, "/api/v1/runs/*/clarify", ControlPlaneRole.OPERATOR),
            new Rule(HttpMethod.POST, "/api/v1/runs/*/replan", ControlPlaneRole.OPERATOR),
            new Rule(HttpMethod.POST, "/api/v1/runs/*/stop", ControlPlaneRole.OPERATOR),
            new Rule(HttpMethod.POST, "/api/v1/runs/*/resume", ControlPlaneRole.OPERATOR),
            new Rule(HttpMethod.POST, "/api/v1/scenarios/*/run", ControlPlaneRole.OPERATOR),
            new Rule(HttpMethod.POST, "/api/v1/runs/*/stages/*/approve", ControlPlaneRole.APPROVER),
            new Rule(HttpMethod.POST, "/api/v1/runs/*/stages/*/reject", ControlPlaneRole.APPROVER),
            new Rule(HttpMethod.POST, "/api/v1/workspace/reindex", ControlPlaneRole.ADMIN),
            new Rule(HttpMethod.POST, "/api/v1/evaluation/suite/run", ControlPlaneRole.ADMIN));

    private static final String API_PATTERN = "/api/**";
    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final PathPattern API = PARSER.parse(API_PATTERN);
    private static final PathPatternRequestMatcher.Builder MATCHERS = PathPatternRequestMatcher.withDefaults();

    private ApiAuthorizationRules() {
    }

    /**
     * One governance command and the role it requires.
     *
     * @param method HTTP method
     * @param pattern path pattern ({@code *} matches one segment)
     * @param role the required role
     */
    public record Rule(HttpMethod method, String pattern, ControlPlaneRole role) {

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return method.equals(requestMethod) && PARSER.parse(pattern).matches(path);
        }
    }

    /**
     * Registers the rules, first match wins.
     *
     * @param auth the authorization registry of the filter chain
     * @param publicDocs whether the OpenAPI document and Swagger UI are public
     */
    static void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
                          boolean publicDocs) {
        auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers(MATCHERS.matcher("/error")).permitAll()
                .requestMatchers(matchers(CONSOLE_PATHS)).permitAll()
                .requestMatchers(matchers(HEALTH_PATHS)).permitAll();
        if (publicDocs) {
            auth.requestMatchers(matchers(DOCS_PATHS)).permitAll();
        }
        for (Rule rule : COMMAND_RULES) {
            auth.requestMatchers(MATCHERS.matcher(rule.method(), rule.pattern())).hasRole(rule.role().name());
        }
        auth.requestMatchers(MATCHERS.matcher(HttpMethod.GET, API_PATTERN)).hasAnyRole(ControlPlaneRole.allNames())
                .requestMatchers(MATCHERS.matcher(API_PATTERN)).hasRole(ControlPlaneRole.ADMIN.name())
                .anyRequest().hasAnyRole(ControlPlaneRole.allNames());
    }

    /**
     * Returns the roles that may call a protected path (any one of them suffices). Used to explain a 403 and to
     * document the operations; public paths are not considered.
     *
     * @param method HTTP method, for example {@code POST}
     * @param path decoded request path without the context path, for example {@code /api/v1/runs/r1/stop}
     * @return the role of the matching governance command; all roles for {@code GET /api/**} and for paths outside
     *         {@code /api}; ADMIN for any other {@code /api/**} request
     */
    public static List<ControlPlaneRole> allowedRoles(String method, String path) {
        HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
        PathContainer container = PathContainer.parsePath(path);
        for (Rule rule : COMMAND_RULES) {
            if (rule.matches(httpMethod, container)) {
                return List.of(rule.role());
            }
        }
        if (!API.matches(container) || HttpMethod.GET.equals(httpMethod)) {
            return List.of(ControlPlaneRole.values());
        }
        return List.of(ControlPlaneRole.ADMIN);
    }

    private static RequestMatcher[] matchers(List<String> patterns) {
        return patterns.stream().map(pattern -> MATCHERS.matcher(HttpMethod.GET, pattern)).toArray(RequestMatcher[]::new);
    }
}
