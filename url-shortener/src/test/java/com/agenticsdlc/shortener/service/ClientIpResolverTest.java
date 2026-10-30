package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.support.TestProperties;

class ClientIpResolverTest {

    private static final String REMOTE = "198.51.100.7";

    private final ClientIpResolver untrusted = new ClientIpResolver(TestProperties.create(true, false));
    private final ClientIpResolver trusted = new ClientIpResolver(TestProperties.create(true, true));

    @Test
    void ignoresForwardedForWhenNotTrusted() {
        assertThat(untrusted.resolve(REMOTE, "203.0.113.9")).isEqualTo(REMOTE);
        assertThat(untrusted.resolve(REMOTE, null)).isEqualTo(REMOTE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        203.0.113.9                          | 203.0.113.9
        '6.6.6.6, 203.0.113.9'               | 203.0.113.9
        '6.6.6.6,203.0.113.9 , '             | 203.0.113.9
        2001:db8::1                          | 2001:db8::1
        203.0.113.9:51234                    | 203.0.113.9
        '6.6.6.6:1, 203.0.113.9:443'         | 203.0.113.9
        '[2001:db8::1]:51234'                | 2001:db8::1
        '[2001:db8::1]'                      | 2001:db8::1
        """)
    void usesRightMostForwardedEntryWhenTrusted(String forwardedFor, String expected) {
        // Proxies that append the client's source port must not give every new connection its own rate-limit bucket.
        // The right-most hop is appended by the trusted proxy; left entries are client-controlled.
        assertThat(trusted.resolve(REMOTE, forwardedFor)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        ''
        '   '
        ' , '
        unknown
        '<script>'
        """)
    void fallsBackToRemoteAddressForBlankOrMalformedForwardedFor(String forwardedFor) {
        assertThat(trusted.resolve(REMOTE, forwardedFor)).isEqualTo(REMOTE);
    }

    @Test
    void fallsBackToRemoteAddressWithoutHeader() {
        assertThat(trusted.resolve(REMOTE, null)).isEqualTo(REMOTE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        198.51.100.7                          | 198.51.100.7
        2001:db8:1:2:aaaa:bbbb:cccc:dddd      | 2001:db8:1:2:0:0:0:0/64
        2001:db8:1:2::1                       | 2001:db8:1:2:0:0:0:0/64
        2001:DB8:1:2:FFFF::                   | 2001:db8:1:2:0:0:0:0/64
        '[2001:db8:1:2::9]'                   | 2001:db8:1:2:0:0:0:0/64
        fe80::1%eth0                          | fe80:0:0:0:0:0:0:0/64
        ::ffff:203.0.113.9                    | 203.0.113.9
        unknown                               | unknown
        abc                                   | abc
        """)
    void rateLimitKeysGroupIpv6ClientsByTheirPrefixAndKeepIpv4(String clientIp, String key) {
        assertThat(untrusted.rateLimitKey(clientIp)).isEqualTo(key);
    }

    @Test
    void theIpv6PrefixLengthIsConfigurableAndAppliesToForwardedAddresses() {
        assertThat(trusted.rateLimitKey(REMOTE, "[2001:db8:1:2::7]:51234")).isEqualTo("2001:db8:1:2:0:0:0:0/64");
        assertThat(resolver(48).rateLimitKey("2001:db8:1:2::7")).isEqualTo("2001:db8:1:0:0:0:0:0/48");
        assertThat(resolver(56).rateLimitKey("2001:db8:1:2ff::7")).isEqualTo("2001:db8:1:200:0:0:0:0/56");
        assertThat(resolver(128).rateLimitKey("2001:db8:1:2::7")).isEqualTo("2001:db8:1:2:0:0:0:7/128");
        assertThat(resolver(32).rateLimitKey("2001:db8:ffff::1")).isEqualTo("2001:db8:0:0:0:0:0:0/32");
        assertThatThrownBy(() -> resolver(31)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ipv6-prefix-length");
        assertThatThrownBy(() -> resolver(129)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ClientIpResolver resolver(int ipv6PrefixLength) {
        ShortenerProperties defaults = TestProperties.create(true, true);
        ShortenerProperties.RateLimit rateLimit = defaults.rateLimit();
        return new ClientIpResolver(new ShortenerProperties(defaults.baseUrl(), defaults.codeLength(),
                defaults.maxTtl(), defaults.blockPrivateTargets(), defaults.resolveTargets(), defaults.resolveTimeout(),
                defaults.trustForwardedHeaders(), new ShortenerProperties.RateLimit(rateLimit.enabled(),
                rateLimit.capacity(), rateLimit.refillPerMinute(), rateLimit.backend(), rateLimit.redisKeyPrefix(),
                ipv6PrefixLength), defaults.analytics(), defaults.security(), defaults.idempotency()));
    }
}
