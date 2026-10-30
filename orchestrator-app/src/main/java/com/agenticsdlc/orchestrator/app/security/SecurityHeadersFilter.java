package com.agenticsdlc.orchestrator.app.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds defensive HTTP response headers to every response (API, console, OpenAPI UI, actuator and error pages):
 * no MIME sniffing, no framing, no referrer, and a Content Security Policy that only allows same-origin scripts,
 * styles (inline styles are allowed for the generated SVG and Swagger UI), images (plus {@code data:} URIs for the
 * console favicon) and connections. The console has no inline scripts or inline event handlers, so
 * {@code script-src 'self'} (inherited from {@code default-src}) holds.
 *
 * <p>The headers are set before the rest of the chain runs, so they are present on short-circuited answers too
 * (for example the 401 of {@link ApiKeyAuthFilter}).</p>
 */
public final class SecurityHeadersFilter extends OncePerRequestFilter {

    /** The Content Security Policy sent with every response. */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; "
            + "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
