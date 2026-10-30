package com.agenticsdlc.shortener.config;

import java.net.URI;
import java.net.URISyntaxException;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;

/**
 * Path decisions of the servlet filters ({@link RateLimitFilter}, {@link ApiKeyFilter}).
 *
 * <p>Filters must decide on the path as Spring MVC routes it, never on the raw request URI: MVC routes
 * {@code /api/v1/links;x}, {@code /api/v1/%6Cinks} and {@code /%61pi/v1/links} to the links controller, so a raw-URI
 * comparison would let such spellings bypass a filter.
 */
final class RequestPaths {

    private RequestPaths() {
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

    /**
     * Returns the normalized path as the {@code instance} of a Problem Details body written by a filter. The path is
     * decoded, so it may contain characters that are illegal in a URI (a space, {@code %}, braces, ...); they are
     * percent-encoded again here instead of failing the request ({@link URI#create} would throw).
     *
     * @param request current request
     * @return the instance URI, or {@code null} in the unlikely case that the path cannot be represented
     */
    static @Nullable URI problemInstance(HttpServletRequest request) {
        try {
            return new URI(null, null, normalizedPath(request), null);
        } catch (URISyntaxException unrepresentable) {
            return null;
        }
    }
}
