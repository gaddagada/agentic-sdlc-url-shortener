package com.agenticsdlc.shortener.service;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.config.ShortenerProperties;

/**
 * Determines the client IP used for rate limiting and visitor hashing.
 *
 * <p>{@code X-Forwarded-For} is honoured only when {@code shortener.trust-forwarded-headers=true}, i.e. when the
 * service runs behind a reverse proxy that is the only way in. In that case the <em>right-most</em> entry is used:
 * it is the address appended by the trusted proxy, whereas entries further left are supplied by the client and can
 * be spoofed. Otherwise the socket peer address is used. Takes plain strings so the service layer stays independent
 * of the Servlet API.
 */
@Component
public class ClientIpResolver {

    /** Name of the forwarded-for header. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Pattern IP_CHARACTERS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final boolean trustForwardedHeaders;

    /**
     * Creates the resolver.
     *
     * @param properties provides {@code shortener.trust-forwarded-headers}
     */
    public ClientIpResolver(ShortenerProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
    }

    /**
     * Resolves the client IP.
     *
     * @param remoteAddress socket peer address
     * @param forwardedFor  value of {@code X-Forwarded-For}, if present
     * @return the client IP
     */
    public String resolve(String remoteAddress, @Nullable String forwardedFor) {
        if (!trustForwardedHeaders || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!hop.isEmpty()) {
                return IP_CHARACTERS.matcher(hop).matches() ? hop : remoteAddress;
            }
        }
        return remoteAddress;
    }
}
