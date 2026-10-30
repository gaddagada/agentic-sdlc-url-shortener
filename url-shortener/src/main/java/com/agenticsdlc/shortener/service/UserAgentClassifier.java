package com.agenticsdlc.shortener.service;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.domain.UserAgentFamily;

/**
 * Maps a {@code User-Agent} header to a coarse {@link UserAgentFamily} with ordered substring rules.
 *
 * <p>Order matters because user agents impersonate each other: bots are checked first, Edge and Opera before
 * Chrome (they contain {@code Chrome/}), and Chrome before Safari (Chrome contains {@code Safari/}). A dedicated
 * parser library would be more precise; this is sufficient for aggregate analytics and keeps the hot path cheap.
 */
@Component
public class UserAgentClassifier {

    private static final List<String> BOT_MARKERS = List.of(
            "bot", "crawler", "spider", "slurp", "facebookexternalhit", "embedly", "preview", "headless",
            "monitor", "lighthouse");
    private static final List<String> HTTP_CLIENT_PREFIXES = List.of(
            "curl/", "wget/", "python-requests", "python-urllib", "python-httpx", "aiohttp", "httpie/",
            "go-http-client", "okhttp", "java/", "java-http-client", "apache-httpclient", "postmanruntime",
            "insomnia", "node-fetch", "axios/", "undici", "libwww-perl", "ruby", "reactor-netty");

    /**
     * Classifies a user agent.
     *
     * @param userAgent raw header value, may be {@code null}
     * @return the family; {@link UserAgentFamily#UNKNOWN} when the header is missing or blank
     */
    public UserAgentFamily classify(@Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UserAgentFamily.UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (BOT_MARKERS.stream().anyMatch(ua::contains)) {
            return UserAgentFamily.BOT;
        }
        if (HTTP_CLIENT_PREFIXES.stream().anyMatch(ua::startsWith)) {
            return UserAgentFamily.HTTP_CLIENT;
        }
        if (ua.contains("edg/") || ua.contains("edge/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return UserAgentFamily.EDGE;
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return UserAgentFamily.OPERA;
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return UserAgentFamily.FIREFOX;
        }
        if (ua.contains("chrome/") || ua.contains("crios/") || ua.contains("chromium/")) {
            return UserAgentFamily.CHROME;
        }
        if (ua.contains("msie ") || ua.contains("trident/")) {
            return UserAgentFamily.IE;
        }
        if (ua.contains("safari/")) {
            return UserAgentFamily.SAFARI;
        }
        return UserAgentFamily.OTHER;
    }
}
