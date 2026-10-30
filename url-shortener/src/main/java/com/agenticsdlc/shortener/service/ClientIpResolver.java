package com.agenticsdlc.shortener.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * client chooses it per connection and every new port would otherwise get a fresh rate-limit bucket. For the same
 * reason the rate-limit key of an IPv6 client is its prefix ({@link #rateLimitKey(String)}); visitor hashing uses the
 * full address ({@link #resolve}). Takes plain strings so the service layer stays independent of the Servlet API.
 */
@Component
public class ClientIpResolver {

    /** Name of the forwarded-for header. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Pattern IP_CHARACTERS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}$");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("^\\[([0-9A-Fa-f:.]{2,45})](?::\\d{1,5})?$");

    private final boolean trustForwardedHeaders;
    private final int ipv6PrefixLength;

    /**
     * Creates the resolver.
     *
     * @param properties provides {@code shortener.trust-forwarded-headers} and
     *                   {@code shortener.rate-limit.ipv6-prefix-length}
     */
    public ClientIpResolver(ShortenerProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
        this.ipv6PrefixLength = properties.rateLimit().ipv6PrefixLength();
    }

    /**
     * Resolves the rate-limit key of the caller: the client IP ({@link #resolve}) reduced by
     * {@link #rateLimitKey(String)}. Used by both rate-limit backends.
     *
     * @param remoteAddress socket peer address
     * @param forwardedFor  value of {@code X-Forwarded-For}, if present
     * @return the rate-limit key
     */
    public String rateLimitKey(String remoteAddress, @Nullable String forwardedFor) {
        return rateLimitKey(resolve(remoteAddress, forwardedFor));
    }

    /**
     * Returns the rate-limit key of a client IP. An IPv4 address is the key as it is. An IPv6 address is reduced to its
     * first {@code shortener.rate-limit.ipv6-prefix-length} bits (default 64) and written as
     * {@code <network address>/<prefix length>}, because an IPv6 subscriber usually controls a whole /64 and could
     * otherwise get a fresh bucket for every address it picks; an IPv4-mapped IPv6 address is keyed by its IPv4
     * address, and a zone ID is ignored. Anything that is not an IP literal is returned unchanged. No DNS lookup is
     * ever made.
     *
     * @param clientIp the client IP (for example from {@link #resolve})
     * @return the rate-limit key
     */
    public String rateLimitKey(String clientIp) {
        String literal = clientIp.strip();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.indexOf(':') < 0 || !IP_CHARACTERS.matcher(literal).matches()) {
            return clientIp; // IPv4 (unchanged) or not an IP literal
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException notALiteral) {
            return clientIp;
        }
        if (address instanceof Inet4Address ipv4) {
            return ipv4.getHostAddress(); // IPv4-mapped IPv6: the IPv4 client
        }
        byte[] bytes = address.getAddress();
        for (int bit = ipv6PrefixLength; bit < bytes.length * 8; bit++) {
            bytes[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
        }
        try {
            return Inet6Address.getByAddress(null, bytes, null).getHostAddress() + "/" + ipv6PrefixLength;
        } catch (UnknownHostException impossible) {
            return clientIp; // unreachable for a 16-byte address
        }
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
