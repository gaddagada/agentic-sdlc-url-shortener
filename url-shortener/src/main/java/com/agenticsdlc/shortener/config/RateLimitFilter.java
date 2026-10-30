package com.agenticsdlc.shortener.config;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.api.ProblemTypes;
import com.agenticsdlc.shortener.service.ClientIpResolver;

import tools.jackson.databind.ObjectMapper;

/**
 * Applies {@link TokenBucketRateLimiter} per client IP to link creation: every {@code POST} whose path starts with
 * {@code /api/v1/links} (today {@code POST /api/v1/links}; future sub-resources such as a bulk endpoint are covered
 * automatically).
 *
 * <p>The decision is made on the decoded, normalized path within the application (servlet path plus path info, with
 * {@code ;} path parameters and duplicate slashes removed), never on the raw request URI. Spring MVC routes
 * {@code /api/v1/links;x} and {@code /api/v1/%6Cinks} to the create endpoint, so a raw-URI comparison would let such
 * spellings bypass the limiter.
 *
 * <p>Rejected requests get {@code 429 Too Many Requests} with a {@code Retry-After} header and a Problem Details
 * body, written here because filters run outside Spring MVC's exception handling. Every evaluated request also
 * receives {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final TokenBucketRateLimiter limiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param limiter          the token bucket limiter
     * @param clientIpResolver resolves the client key, honouring forwarded headers only when trusted
     * @param objectMapper     serializes the Problem Details body
     */
    public RateLimitFilter(TokenBucketRateLimiter limiter, ClientIpResolver clientIpResolver,
                           ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && normalizedPath(request).startsWith(LinkController.BASE_PATH));
    }

    /**
     * Returns the decoded path within the application as the servlet container routed it: servlet path plus path
     * info (both already percent-decoded and dot-segment normalized by the container), with {@code ;} path parameters
     * stripped from every segment and duplicate slashes collapsed as a defence in depth.
     *
     * @param request current request
     * @return the normalized path, starting with {@code /} unless empty
     */
    static String normalizedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        StringBuilder normalized = new StringBuilder(path.length());
        boolean inPathParameter = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                inPathParameter = false;
                if (normalized.isEmpty() || normalized.charAt(normalized.length() - 1) != '/') {
                    normalized.append(c);
                }
            } else if (c == ';') {
                inPathParameter = true;
            } else if (!inPathParameter) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientIpResolver.resolve(request.getRemoteAddr(),
                request.getHeader(ClientIpResolver.FORWARDED_FOR));
        TokenBucketRateLimiter.Decision decision = limiter.tryConsume(client);
        response.setHeader(LIMIT_HEADER, Integer.toString(limiter.capacity()));
        response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("rate limit exceeded path={} retryAfterSeconds={}", normalizedPath(request),
                decision.retryAfterSeconds());
        writeTooManyRequests(request, response, decision.retryAfterSeconds());
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many link creation requests from this client; retry after " + retryAfterSeconds + " seconds.");
        problem.setType(ProblemTypes.RATE_LIMITED);
        problem.setTitle("Too many requests");
        // The normalized path, not the raw URI: it is what was rate limited and never reflects path parameters.
        problem.setInstance(URI.create(normalizedPath(request)));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
