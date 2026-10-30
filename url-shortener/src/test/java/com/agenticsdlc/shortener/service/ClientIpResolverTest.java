package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        """)
    void usesRightMostForwardedEntryWhenTrusted(String forwardedFor, String expected) {
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
}
