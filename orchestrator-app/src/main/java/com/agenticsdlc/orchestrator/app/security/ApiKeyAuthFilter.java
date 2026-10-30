package com.agenticsdlc.orchestrator.app.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Protects {@code /api/**} with a shared API key sent in {@code X-Api-Key} (prototype security model, ADR 0009):
 * a missing or wrong key is answered with a 401 Problem Detail. The static console, actuator and OpenAPI endpoints
 * are never behind the key. Keys are compared as SHA-256 digests with {@link MessageDigest#isEqual} (constant time,
 * no length leak) and never logged.
 */
public final class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Header carrying the API key. */
    public static final String HEADER = "X-Api-Key";

    /** Problem type of a 401 answer. */
    static final String PROBLEM_TYPE = "https://agenticsdlc.dev/problems/unauthorized";

    private static final String PROTECTED_PREFIX = "/api/";

    private final byte[] expectedDigest;
    private final JsonMapper mapper;

    /**
     * Creates the filter.
     *
     * @param apiKey the expected key (not blank)
     * @param mapper JSON mapper used to write the Problem Detail
     */
    public ApiKeyAuthFilter(String apiKey, JsonMapper mapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("the API key must not be blank");
        }
        this.expectedDigest = digest(apiKey);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PROTECTED_PREFIX);
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
        problem.put("detail", "A valid " + HEADER + " header is required for /api/** requests.");
        problem.put("instance", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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
