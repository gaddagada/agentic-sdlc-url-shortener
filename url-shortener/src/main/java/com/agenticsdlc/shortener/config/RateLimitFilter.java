package com.agenticsdlc.shortener.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import com.agenticsdlc.shortener.api.ApiHeaders;
import com.agenticsdlc.shortener.api.BulkLinkController;
import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.api.ProblemMapper;
import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.service.RateLimiter;

import tools.jackson.databind.ObjectMapper;

/**
 * Charges one {@link RateLimiter} token per client (an IPv4 address, or an IPv6 prefix of
 * {@code shortener.rate-limit.ipv6-prefix-length} bits: {@code ClientIpResolver#rateLimitKey}) for every {@code POST}
 * whose path starts with
 * {@code /api/v1/links} (today {@code POST /api/v1/links}), except the bulk endpoint
 * {@code POST /api/v1/links/bulk}: that one is charged one token per item by {@code BulkLinkService} once the body has
 * been parsed. Only the exact (normalized) bulk path is exempt, so any other spelling that might still reach the bulk
 * handler is charged here as well: a request can be charged twice, never zero times.
 *
 * <p>The decision is made on the decoded, normalized path ({@link RequestPaths#normalizedPath}), never on the raw
 * request URI. Spring MVC routes {@code /api/v1/links;x} and {@code /api/v1/%6Cinks} to the create endpoint, so a
 * raw-URI comparison would let such spellings bypass the limiter.
 *
 * <p>Rejected requests get {@code 429 Too Many Requests} with a {@code Retry-After} header and a Problem Details
 * body (built by the shared {@link ProblemMapper}, identical to the bulk endpoint's 429), written here because
 * filters run outside Spring MVC's exception handling. Every evaluated request also receives
 * {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = ApiHeaders.RATE_LIMIT_LIMIT;
    static final String REMAINING_HEADER = ApiHeaders.RATE_LIMIT_REMAINING;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param limiter          the token bucket limiter
     * @param clientIpResolver resolves the client key, honouring forwarded headers only when trusted
     * @param objectMapper     serializes the Problem Details body
     */
    public RateLimitFilter(RateLimiter limiter, ClientIpResolver clientIpResolver, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = RequestPaths.normalizedPath(request);
        return !path.startsWith(LinkController.BASE_PATH) || path.equals(BulkLinkController.PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientIpResolver.rateLimitKey(request.getRemoteAddr(),
                request.getHeader(ClientIpResolver.FORWARDED_FOR));
        RateLimiter.Decision decision = limiter.tryConsume(client);
        response.setHeader(LIMIT_HEADER, Integer.toString(limiter.capacity()));
        response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("rate limit exceeded path={} retryAfterSeconds={}", RequestPaths.normalizedPath(request),
                decision.retryAfterSeconds());
        writeTooManyRequests(request, response, decision.retryAfterSeconds());
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        ProblemDetail problem = ProblemMapper.rateLimited(
                "Too many link creation requests from this client; retry after " + retryAfterSeconds + " seconds.");
        // The normalized path, not the raw URI: it is what was rate limited and never reflects path parameters.
        problem.setInstance(RequestPaths.problemInstance(request));
        response.setStatus(problem.getStatus());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
