package com.agenticsdlc.shortener.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.service.exception.UrlRejectedException;

/**
 * Enforces the target URL policy.
 *
 * <ul>
 *   <li>absolute {@code http}/{@code https} URL with a host, at most {@value #MAX_LENGTH} characters;</li>
 *   <li>no user-info (credentials) in the authority;</li>
 *   <li>when {@code shortener.block-private-targets=true}: no {@code localhost}, no {@code .local}/{@code .internal}
 *       host names and no IP literal in loopback, private, carrier-grade NAT, link-local (including the cloud metadata
 *       address {@code 169.254.169.254}), multicast, unspecified or reserved ranges.</li>
 * </ul>
 *
 * <p>IP literals are parsed without DNS. Dotted quads are checked both as decimal and with POSIX
 * {@code inet_aton} semantics (octal/hex parts such as {@code 0177.0.0.1}), and a host is blocked if either reading is
 * non-public. Host names are deliberately not resolved: a public name that resolves to a private address is not
 * detected (documented trade-off, the shortener never fetches targets itself).
 */
@Component
public class UrlValidator {

    /** Maximum accepted URL length. */
    public static final int MAX_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final List<String> BLOCKED_HOST_SUFFIXES = List.of(".local", ".internal", ".localhost");
    private static final String METADATA_ADDRESS = "169.254.169.254";

    private final boolean blockPrivateTargets;

    /**
     * Creates the validator.
     *
     * @param properties provides {@code shortener.block-private-targets}
     */
    public UrlValidator(ShortenerProperties properties) {
        this.blockPrivateTargets = properties.blockPrivateTargets();
    }

    /**
     * Validates a target URL.
     *
     * @param rawUrl URL as supplied by the client
     * @return the parsed URL (surrounding whitespace removed)
     * @throws UrlRejectedException with a client-safe reason when the URL violates the policy
     */
    public URI validate(@Nullable String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UrlRejectedException("URL must not be blank.");
        }
        String candidate = rawUrl.strip();
        if (candidate.length() > MAX_LENGTH) {
            throw new UrlRejectedException("URL must be at most " + MAX_LENGTH + " characters long.");
        }
        URI uri = parse(candidate);
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new UrlRejectedException("Only absolute http and https URLs are allowed.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UrlRejectedException("URLs with embedded credentials are not allowed.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UrlRejectedException("URL must contain a valid host name.");
        }
        if (blockPrivateTargets) {
            rejectNonPublicHost(host);
        }
        return uri;
    }

    private static URI parse(String candidate) {
        try {
            return new URI(candidate);
        } catch (URISyntaxException e) {
            throw new UrlRejectedException("URL is not a valid absolute URI.");
        }
    }

    private static void rejectNonPublicHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || BLOCKED_HOST_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw new UrlRejectedException("Target host '" + rawHost + "' is a local or internal host name.");
        }
        for (InetAddress address : ipLiteralReadings(host)) {
            if (isNonPublic(address)) {
                throw new UrlRejectedException(
                        "Target host '" + rawHost + "' is a private, loopback or otherwise non-public address.");
            }
        }
    }

    /** All plausible IP interpretations of {@code host} without any DNS lookup; empty for host names. */
    private static List<InetAddress> ipLiteralReadings(String host) {
        List<InetAddress> readings = new ArrayList<>(2);
        if (host.startsWith("[") && host.endsWith("]")) {
            addIfLiteral(readings, host.substring(1, host.length() - 1), false);
            return readings;
        }
        addIfLiteral(readings, host, false);
        addIfLiteral(readings, host, true);
        return readings;
    }

    private static void addIfLiteral(List<InetAddress> readings, String text, boolean posix) {
        try {
            readings.add(posix ? Inet4Address.ofPosixLiteral(text) : InetAddress.ofLiteral(text));
        } catch (IllegalArgumentException notALiteral) {
            // a host name, or not representable in this notation
        }
    }

    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || METADATA_ADDRESS.equals(address.getHostAddress())) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isReservedIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            return isNonPublicIpv6(bytes);
        }
        return false;
    }

    private static boolean isReservedIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0                                   // 0.0.0.0/8 "this network"
                || first == 100 && (second & 0xc0) == 64    // 100.64.0.0/10 carrier-grade NAT
                || first >= 240;                            // 240.0.0.0/4 reserved, incl. broadcast
    }

    private static boolean isNonPublicIpv6(byte[] b) {
        if ((b[0] & 0xfe) == 0xfc) {
            return true;                                    // fc00::/7 unique local
        }
        boolean ipv4Compatible = allZero(b, 0, 12);         // ::a.b.c.d (deprecated)
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12); // 64:ff9b::/96
        if (ipv4Compatible || nat64) {
            byte[] embedded = {b[12], b[13], b[14], b[15]};
            try {
                return isNonPublic(InetAddress.getByAddress(embedded));
            } catch (UnknownHostException impossible) {
                return true; // unreachable for a 4-byte array; fail closed
            }
        }
        return false;
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
