package com.agenticsdlc.shortener.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.agenticsdlc.shortener.service.HostResolver;

/**
 * Programmable {@link HostResolver}: answers from a table, simulates unknown hosts and timeouts, counts lookups.
 * Hosts not in the table resolve to {@link #DEFAULT_PUBLIC_ADDRESS} unless {@link #unknownByDefault()} was called.
 */
public final class FakeHostResolver implements HostResolver {

    /** A public address (TEST-NET would be non-public under some rules, so a real public one is used). */
    public static final String DEFAULT_PUBLIC_ADDRESS = "93.184.216.34";

    private final Map<String, List<InetAddress>> answers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> timeouts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> lookups = new ConcurrentHashMap<>();
    private volatile boolean unknownByDefault;

    /**
     * Makes {@code host} resolve to {@code addresses} (IP literals).
     *
     * @param host      host name
     * @param addresses IP literals
     * @return this resolver
     */
    public FakeHostResolver answer(String host, String... addresses) {
        answers.put(host, Arrays.stream(addresses).map(InetAddress::ofLiteral).toList());
        timeouts.remove(host);
        return this;
    }

    /**
     * Makes lookups of {@code host} time out.
     *
     * @param host host name
     * @return this resolver
     */
    public FakeHostResolver timeout(String host) {
        timeouts.put(host, true);
        return this;
    }

    /** {@return this resolver, answering "unknown host" for hosts without an explicit answer} */
    public FakeHostResolver unknownByDefault() {
        unknownByDefault = true;
        return this;
    }

    /**
     * Number of lookups of a host so far.
     *
     * @param host host name
     * @return lookup count
     */
    public int lookups(String host) {
        AtomicInteger count = lookups.get(host);
        return count == null ? 0 : count.get();
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        lookups.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
        if (timeouts.containsKey(host)) {
            throw new ResolutionTimeoutException(host + ": simulated timeout");
        }
        List<InetAddress> answer = answers.get(host);
        if (answer != null) {
            return answer;
        }
        if (unknownByDefault) {
            throw new UnknownHostException(host);
        }
        return List.of(InetAddress.ofLiteral(DEFAULT_PUBLIC_ADDRESS));
    }
}
