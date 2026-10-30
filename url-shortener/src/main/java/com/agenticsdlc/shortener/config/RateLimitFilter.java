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
 * Applies {@link TokenBucketRateLimiter} per client IP to link creation ({@code POST /api/v1/links}).
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
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(HttpMethod.POST.matches(request.getMethod()) && LinkController.BASE_PATH.equals(path));
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
        log.debug("rate limit exceeded path={} retryAfterSeconds={}", request.getRequestURI(),
                decision.retryAfterSeconds());
        writeTooManyRequests(request, response, decision.retryAfterSeconds());
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many link creation requests from this client; retry after " + retryAfterSeconds + " seconds.");
        problem.setType(ProblemTypes.RATE_LIMITED);
        problem.setTitle("Too many requests");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
