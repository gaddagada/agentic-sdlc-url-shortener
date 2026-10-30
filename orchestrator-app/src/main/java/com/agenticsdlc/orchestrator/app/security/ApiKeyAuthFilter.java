package com.agenticsdlc.orchestrator.app.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * Protects {@code /api/**} with a shared API key sent in {@code X-Api-Key} ({@code sdlc.security.mode=api-key},
 * ADR 0009): a missing or wrong key is answered with a 401 Problem Detail and a {@code WWW-Authenticate} challenge
 * naming the header. The static console and actuator are never behind the key; the OpenAPI document and Swagger UI
 * ({@code /v3/api-docs}, {@code /v3/api-docs/**}, {@code /v3/api-docs.yaml}, {@code /swagger-ui.html},
 * {@code /swagger-ui/**}) are behind it only when {@code sdlc.security.public-docs=false} (in this mode every caller
 * past the filter holds every role, so the filter is what keeps the documents private). Keys are compared as SHA-256
 * digests with {@link MessageDigest#isEqual} (constant time, no length leak) and never logged.
 *
 * <p>The filter is registered for every path ({@code /*}) and decides itself whether a request is an API request,
 * on <em>normalized</em> paths only: the container's decoded {@code servletPath + pathInfo} and, as a fail-closed
 * second opinion, the request URI after percent-decoding. Both views are stripped of {@code ;} path parameters,
 * dot segments and duplicate slashes and compared case-insensitively, so encodings such as {@code /%61pi/v1/runs},
 * {@code /api;x=1/v1/runs} or {@code /api/v1//runs} cannot route to a controller without the key. The same
 * normalized views decide whether a request targets the documents.</p>
 */
public final class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Header carrying the API key. */
    public static final String HEADER = "X-Api-Key";

    /** {@code WWW-Authenticate} challenge of a 401 answer (names the header to send). */
    public static final String CHALLENGE = "ApiKey realm=\"sdlc-orchestrator\", header=\"" + HEADER + "\"";

    /** Problem type of a 401 answer. */
    static final String PROBLEM_TYPE = "https://agenticsdlc.dev/problems/unauthorized";

    private static final String PROTECTED_ROOT = "/api";

    private final byte[] expectedDigest;
    private final JsonMapper mapper;
    private final boolean protectDocs;

    /**
     * Creates the filter for {@code /api/**} only (public documents).
     *
     * @param apiKey the expected key (not blank)
     * @param mapper JSON mapper used to write the Problem Detail
     */
    public ApiKeyAuthFilter(String apiKey, JsonMapper mapper) {
        this(apiKey, mapper, false);
    }

    /**
     * Creates the filter.
     *
     * @param apiKey the expected key (not blank)
     * @param mapper JSON mapper used to write the Problem Detail
     * @param protectDocs whether the OpenAPI document and Swagger UI need the key too
     *                    ({@code sdlc.security.public-docs=false})
     */
    public ApiKeyAuthFilter(String apiKey, JsonMapper mapper, boolean protectDocs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("the API key must not be blank");
        }
        this.expectedDigest = digest(apiKey);
        this.mapper = mapper;
        this.protectDocs = protectDocs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isApiRequest(request) && !(protectDocs && isDocsRequest(request));
    }

    /**
     * Returns whether a request targets the protected API, judged on normalized paths only (never on the raw URI
     * text alone): the decoded servlet path plus path info, and the percent-decoded request URI.
     *
     * @param request the request
     * @return true when either normalized view of the path is {@code /api} or below it
     */
    static boolean isApiRequest(HttpServletRequest request) {
        return anyNormalizedView(request, ApiKeyAuthFilter::isProtected);
    }

    /**
     * Returns whether a request targets the OpenAPI document or Swagger UI, judged on the same normalized views as
     * {@link #isApiRequest}.
     *
     * @param request the request
     * @return true when either normalized view of the path is a document path
     */
    static boolean isDocsRequest(HttpServletRequest request) {
        return anyNormalizedView(request, ApiKeyAuthFilter::isDocs);
    }

    private static boolean anyNormalizedView(HttpServletRequest request, Predicate<String> test) {
        String servletView = (request.getServletPath() == null ? "" : request.getServletPath())
                + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (test.test(normalize(servletView))) {
            return true;
        }
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        if (!contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String decoded;
        try {
            decoded = UriUtils.decode(stripPathParameters(uri), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // malformed percent-encoding: judge the undecoded text (the container rejects such requests anyway)
            decoded = uri;
        }
        return test.test(normalize(decoded));
    }

    /**
     * Normalizes a decoded path: removes {@code ;} parameters from every segment, turns backslashes into slashes,
     * collapses duplicate slashes and resolves {@code .} and {@code ..} segments.
     *
     * @param path a decoded path
     * @return the normalized path (starting with {@code /}, or empty)
     */
    static String normalize(String path) {
        if (path.isEmpty()) {
            return path;
        }
        String cleaned = stripPathParameters(path.replace('\\', '/')).replaceAll("/{2,}", "/");
        cleaned = StringUtils.cleanPath(cleaned);
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        // dot segments that climb above the root cannot be resolved; drop them (fail closed)
        while (cleaned.equals("/..") || cleaned.startsWith("/../")) {
            cleaned = cleaned.length() == 3 ? "/" : cleaned.substring(3);
        }
        return cleaned;
    }

    private static String stripPathParameters(String path) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder(path.length());
        boolean inParameter = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                inParameter = false;
                out.append(c);
            } else if (c == ';') {
                inParameter = true;
            } else if (!inParameter) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isProtected(String normalizedPath) {
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        return lower.equals(PROTECTED_ROOT) || lower.startsWith(PROTECTED_ROOT + "/");
    }

    private static boolean isDocs(String normalizedPath) {
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        return lower.equals("/v3/api-docs") || lower.startsWith("/v3/api-docs/") || lower.startsWith("/v3/api-docs.")
                || lower.equals("/swagger-ui.html") || lower.equals("/swagger-ui")
                || lower.startsWith("/swagger-ui/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", PROBLEM_TYPE);
        problem.put("title", "Unauthorized");
        problem.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        problem.put("detail", "A valid " + HEADER + " header is required for /api/** requests"
                + (protectDocs ? " and the API documentation." : "."));
        problem.put("instance", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(problem));
    }

    private boolean matches(@Nullable String provided) {
        return provided != null && MessageDigest.isEqual(expectedDigest, digest(provided));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
