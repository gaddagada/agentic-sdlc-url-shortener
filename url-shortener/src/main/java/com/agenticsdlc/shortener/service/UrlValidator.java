package com.agenticsdlc.shortener.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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
 *       address {@code 169.254.169.254}), multicast, unspecified or reserved ranges;</li>
 *   <li>additionally, when {@code shortener.resolve-targets=true}: no host name resolving to such an address (see
 *       below).</li>
 * </ul>
 *
 * <p>IP literals are parsed without DNS. Dotted quads are checked both as decimal and with POSIX
 * {@code inet_aton} semantics (octal/hex parts such as {@code 0177.0.0.1}), and a host is blocked if either reading is
 * non-public. A bracketed IPv6 literal is judged without its zone ID ({@code [fe80::1%25eth0]} is link-local), and a
 * bracketed host that is not an IP literal is rejected. IPv6 addresses that embed an IPv4 address (IPv4-mapped,
 * IPv4-compatible, IPv4-translated {@code ::ffff:0:0/96}, NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}) are
 * judged by the embedded address; the local-use NAT64 prefix {@code 64:ff9b:1::/48} is always non-public. A Teredo
 * address ({@code 2001::/32}) is judged by both IPv4 addresses it embeds, the server's and the (bit-inverted) client's.
 *
 * <p><b>Resolve-time check (optional, {@code shortener.resolve-targets=true}).</b> By default host names are not
 * resolved: a public-looking name that resolves to a private address is not detected (the shortener never fetches
 * targets itself). With {@code resolve-targets} (and {@code block-private-targets}) every host name is resolved through
 * the {@link HostResolver} (default: the platform resolver, bounded by {@code shortener.resolve-timeout}, 2 s) and the
 * URL is rejected when <em>any</em> resolved address is non-public, using the same rules as for IP literals. A name
 * that does not resolve, or not in time, is rejected as well (fail closed, {@code 422 url-rejected} with a clear
 * reason). Verdicts (public / non-public) are cached per host for {@value #VERDICT_TTL_SECONDS} s, bounded to
 * {@value #MAX_CACHED_VERDICTS} hosts; resolution failures are not cached, so a transient DNS problem can be retried.
 * Items of a bulk request are validated one after the other, so a batch of unresolvable hosts can take up to
 * {@code resolve-timeout} per distinct host.
 *
 * <p><b>DNS rebinding caveat.</b> The check only sees the answers at creation time (or up to
 * {@value #VERDICT_TTL_SECONDS} s earlier). An attacker controlling a domain can answer with a public address now and
 * a private one later (low TTL, or different answers per resolver), so a URL accepted here may point inside the
 * network when it is eventually fetched. This makes the check a filter against obvious internal targets, not an SSRF
 * defence for whoever follows the links: a component that fetches target URLs (previews, crawlers) must resolve once,
 * validate and connect to that same address (or go through an egress proxy that enforces the policy).
 */
@Component
public class UrlValidator {

    /** Maximum accepted URL length. */
    public static final int MAX_LENGTH = 2048;

    static final long VERDICT_TTL_SECONDS = 60;
    static final long MAX_CACHED_VERDICTS = 10_000;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final List<String> BLOCKED_HOST_SUFFIXES = List.of(".local", ".internal", ".localhost");
    private static final String METADATA_ADDRESS = "169.254.169.254";

    private final boolean blockPrivateTargets;
    private final boolean resolveTargets;
    private final HostResolver hostResolver;
    private final Duration resolveTimeout;
    /** Host name to "every resolved address is public". */
    private final Cache<String, Boolean> verdicts;

    /**
     * Creates the validator.
     *
     * @param properties   provides {@code shortener.block-private-targets}, {@code resolve-targets} and
     *                     {@code resolve-timeout}
     * @param hostResolver resolves host names when {@code resolve-targets} is on
     * @param clock        time source of the verdict cache
     */
    public UrlValidator(ShortenerProperties properties, HostResolver hostResolver, Clock clock) {
        this.blockPrivateTargets = properties.blockPrivateTargets();
        this.resolveTargets = properties.resolveTargets();
        this.hostResolver = hostResolver;
        this.resolveTimeout = properties.resolveTimeout();
        this.verdicts = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_VERDICTS)
                .expireAfterWrite(Duration.ofSeconds(VERDICT_TTL_SECONDS))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
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

    private void rejectNonPublicHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || BLOCKED_HOST_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw new UrlRejectedException("Target host '" + rawHost + "' is a local or internal host name.");
        }
        List<InetAddress> literalReadings = ipLiteralReadings(host);
        if (literalReadings.isEmpty() && host.startsWith("[")) {
            // A bracketed host can only be an IP literal; one that cannot be parsed cannot be judged (fail closed).
            throw new UrlRejectedException("Target host '" + rawHost + "' is not a supported IP address literal.");
        }
        for (InetAddress address : literalReadings) {
            if (isNonPublic(address)) {
                throw new UrlRejectedException(
                        "Target host '" + rawHost + "' is a private, loopback or otherwise non-public address.");
            }
        }
        if (resolveTargets && literalReadings.isEmpty()) {
            rejectIfResolvesToNonPublic(rawHost, host);
        }
    }

    /**
     * Resolve-time check of a host name. The cache is read and written around the lookup instead of computing inside
     * it, so a slow lookup never blocks other hosts; concurrent first lookups of the same host may both resolve.
     */
    private void rejectIfResolvesToNonPublic(String rawHost, String host) {
        Boolean allPublic = verdicts.getIfPresent(host);
        if (allPublic == null) {
            allPublic = resolvesToPublicAddressesOnly(rawHost, host);
            verdicts.put(host, allPublic);
        }
        if (!allPublic) {
            throw new UrlRejectedException("Target host '" + rawHost
                    + "' resolves to a private, loopback or otherwise non-public address.");
        }
    }

    private boolean resolvesToPublicAddressesOnly(String rawHost, String host) {
        List<InetAddress> addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (HostResolver.ResolutionTimeoutException slow) {
            throw new UrlRejectedException("Target host '" + rawHost + "' could not be resolved within "
                    + resolveTimeout.toMillis() + " ms; please retry.");
        } catch (UnknownHostException unknown) {
            throw new UrlRejectedException("Target host '" + rawHost + "' could not be resolved.");
        }
        if (addresses.isEmpty()) {
            throw new UrlRejectedException("Target host '" + rawHost + "' could not be resolved.");
        }
        return addresses.stream().noneMatch(UrlValidator::isNonPublic);
    }

    /** All plausible IP interpretations of {@code host} without any DNS lookup; empty for host names. */
    private static List<InetAddress> ipLiteralReadings(String host) {
        List<InetAddress> readings = new ArrayList<>(2);
        if (host.startsWith("[") && host.endsWith("]")) {
            String literal = host.substring(1, host.length() - 1);
            // RFC 6874 zone ID ("%25eth0" in a URI): the address without it decides (fe80::1%25eth0 is link-local).
            int zone = literal.indexOf('%');
            addIfLiteral(readings, zone < 0 ? literal : literal.substring(0, zone), false);
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
        boolean nat64Prefix = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b;
        if (nat64Prefix && b[4] == 0 && b[5] == 1) {
            return true;                                    // 64:ff9b:1::/48 local-use NAT64 (RFC 8215)
        }
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
            return isNonPublicIpv4(b, 2);                   // 2002::/16 6to4, IPv4 in bits 16..47
        }
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) {
            // 2001::/32 Teredo (RFC 4380): the server IPv4 in bits 32..63, the client IPv4 in bits 96..127 with every
            // bit inverted ("obfuscated"); a tunnel to either one reaches that IPv4 address
            byte[] client = {(byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15]};
            return isNonPublicIpv4(b, 4) || isNonPublicIpv4(client, 0);
        }
        boolean ipv4Compatible = allZero(b, 0, 12);         // ::a.b.c.d (deprecated)
        boolean nat64 = nat64Prefix && allZero(b, 4, 12);   // 64:ff9b::/96
        boolean ipv4Translated = allZero(b, 0, 8) && (b[8] & 0xff) == 0xff && (b[9] & 0xff) == 0xff
                && b[10] == 0 && b[11] == 0;                 // ::ffff:0:a.b.c.d (SIIT, RFC 2765)
        if (ipv4Compatible || nat64 || ipv4Translated) {
            return isNonPublicIpv4(b, 12);
        }
        return false;
    }

    /** Whether the IPv4 address in {@code b[from..from+3]} is non-public. */
    private static boolean isNonPublicIpv4(byte[] b, int from) {
        byte[] embedded = {b[from], b[from + 1], b[from + 2], b[from + 3]};
        try {
            return isNonPublic(InetAddress.getByAddress(embedded));
        } catch (UnknownHostException impossible) {
            return true; // unreachable for a 4-byte array; fail closed
        }
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
