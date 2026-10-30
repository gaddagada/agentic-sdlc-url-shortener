package com.agenticsdlc.shortener.service;

import java.util.regex.Matcher;
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
 * be spoofed. Otherwise the socket peer address is used. Some proxies (for example Azure Application Gateway) append
 * the client's source port ({@code 203.0.113.7:51234}, {@code [2001:db8::7]:51234}); the port is removed, because the
 * client chooses it per connection and every new port would otherwise get a fresh rate-limit bucket. Takes plain
 * strings so the service layer stays independent of the Servlet API.
 */
@Component
public class ClientIpResolver {

    /** Name of the forwarded-for header. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Pattern IP_CHARACTERS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}$");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("^\\[([0-9A-Fa-f:.]{2,45})](?::\\d{1,5})?$");

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
                return withoutPort(hop, remoteAddress);
            }
        }
        return remoteAddress;
    }

    /** The address of a forwarded hop without a port, or {@code fallback} when the hop is not an IP address. */
    private static String withoutPort(String hop, String fallback) {
        Matcher ipv4 = IPV4_WITH_PORT.matcher(hop);
        if (ipv4.matches()) {
            return ipv4.group(1);
        }
        Matcher ipv6 = BRACKETED_IPV6.matcher(hop);
        if (ipv6.matches()) {
            return ipv6.group(1);
        }
        return IP_CHARACTERS.matcher(hop).matches() ? hop : fallback;
    }
}
