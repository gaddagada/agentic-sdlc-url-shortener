package com.agenticsdlc.shortener.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import com.agenticsdlc.shortener.api.ApiHeaders;
import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.api.ProblemMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * Optional authentication of the management API with a shared key ({@code shortener.security.api-key}).
 *
 * <p>When a key is configured, every request whose decoded, normalized path ({@link RequestPaths#normalizedPath})
 * starts with {@code /api/v1/links} must carry it in {@code X-Api-Key}: link creation (single and bulk), metadata,
 * analytics and deactivation, whatever the method ({@code HEAD} is served by the {@code GET} handlers), except
 * {@code OPTIONS}, which carries no data. Redirects ({@code GET /{code}}), actuator and the OpenAPI/Swagger endpoints
 * stay public. Deciding on the normalized path, not the raw URI, means spellings that Spring MVC routes to the links
 * controllers ({@code /%61pi/v1/links}, {@code /api/v1/links;x}, {@code //api/v1/links}) are protected too.
 *
 * <p>The key is compared in constant time: both values are hashed with SHA-256 and the fixed-length digests are
 * compared with {@link MessageDigest#isEqual}, so neither the content nor the length of the key leaks through timing.
 * A missing and a wrong key get the same {@code 401} Problem Details response with {@code WWW-Authenticate}.
 * Without a configured key the filter lets everything through (the configuration logs a warning at startup).
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String WWW_AUTHENTICATE_VALUE = "ApiKey realm=\"url-shortener\"";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte @Nullable [] expectedDigest;
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param apiKey       the expected key, surrounding whitespace ignored (e.g. a trailing newline of a secret
     *                     file); {@code null} or blank leaves the API open
     * @param objectMapper serializes the Problem Details body
     */
    public ApiKeyFilter(@Nullable String apiKey, ObjectMapper objectMapper) {
        this.expectedDigest = apiKey == null || apiKey.isBlank() ? null : sha256(apiKey.strip());
        this.objectMapper = objectMapper;
    }

    /** {@return whether a key is configured, i.e. the filter enforces authentication} */
    public boolean isEnforcing() {
        return expectedDigest != null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expectedDigest == null
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || !RequestPaths.normalizedPath(request).startsWith(LinkController.BASE_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request.getHeader(ApiHeaders.API_KEY))) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("rejected unauthenticated request method={} path={}", request.getMethod(),
                RequestPaths.normalizedPath(request));
        ProblemDetail problem = ProblemMapper.unauthorized();
        problem.setInstance(URI.create(RequestPaths.normalizedPath(request)));
        response.setStatus(problem.getStatus());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /**
     * Compares a presented key with the configured one in constant time.
     *
     * @param presented header value, possibly {@code null}
     * @return whether it equals the configured key
     */
    boolean matches(@Nullable String presented) {
        if (expectedDigest == null) {
            return true;
        }
        // Hash even a missing key so that both paths do the same work.
        byte[] presentedDigest = sha256(presented == null ? "" : presented);
        return MessageDigest.isEqual(expectedDigest, presentedDigest) && presented != null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
