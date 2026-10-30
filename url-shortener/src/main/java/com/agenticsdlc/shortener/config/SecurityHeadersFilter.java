package com.agenticsdlc.shortener.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds baseline security headers to every response: {@code X-Content-Type-Options: nosniff},
 * {@code Referrer-Policy: no-referrer} and {@code X-Frame-Options: DENY}.
 *
 * <p>The headers are set before the rest of the chain runs, so responses written by later filters (such as the
 * {@code 429} of {@link RateLimitFilter}) and error responses carry them too. Other headers, notably
 * {@code Cache-Control} and {@code Location} of redirects, are left untouched. Because {@code Referrer-Policy} applies
 * to the request a browser makes when following a redirect, destination sites of short links receive no
 * {@code Referer}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    static final String REFERRER_POLICY = "Referrer-Policy";
    static final String FRAME_OPTIONS = "X-Frame-Options";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
        response.setHeader(REFERRER_POLICY, "no-referrer");
        response.setHeader(FRAME_OPTIONS, "DENY");
        chain.doFilter(request, response);
    }
}
