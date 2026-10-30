package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.agenticsdlc.shortener.service.exception.UrlRejectedException;
import com.agenticsdlc.shortener.support.FakeHostResolver;
import com.agenticsdlc.shortener.support.MutableClock;
import com.agenticsdlc.shortener.support.TestProperties;

class UrlValidatorTest {

    private final UrlValidator validator = TestProperties.urlValidator(TestProperties.defaults());

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com",
        "http://example.com/path?q=1&r=two#fragment",
        "HTTPS://Example.COM/Mixed/Case",
        "https://sub.domain.example.co.uk:8443/a/b",
        "https://93.184.216.34/",
        "https://[2606:2800:220:1:248:1893:25c8:1946]/",
        "https://[2002:5db8:d822::1]/",
        "https://[64:ff9b::5db8:d822]/",
        "http://localhost.example.com/",
        "https://example.com/" + "a",
        "  https://example.com/surrounded-by-whitespace  "
    })
    void acceptsPublicHttpUrls(String url) {
        URI uri = validator.validate(url);

        assertThat(uri.toString()).isEqualTo(url.strip());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        http://127.0.0.1/                           | non-public
        http://127.1.2.3:8080/admin                 | non-public
        http://10.0.0.1/                            | non-public
        http://172.16.5.4/                          | non-public
        http://192.168.1.1/                         | non-public
        http://169.254.169.254/latest/meta-data/    | non-public
        http://[::1]/                               | non-public
        http://[::]/                                | non-public
        http://[fc00::1]/                           | non-public
        http://[fe80::1]/                           | non-public
        http://[::ffff:127.0.0.1]/                  | non-public
        http://[64:ff9b::7f00:1]/                   | non-public
        http://0.0.0.0/                             | non-public
        http://100.64.0.1/                          | non-public
        http://224.0.0.1/                           | non-public
        http://255.255.255.255/                     | non-public
        http://localhost/                           | local or internal
        http://LOCALHOST:8080/                      | local or internal
        http://localhost./                          | local or internal
        http://printer.local/                       | local or internal
        http://metadata.google.internal/            | local or internal
        http://app.localhost/                       | local or internal
        ftp://example.com/file.txt                  | http and https
        file:///etc/passwd                          | http and https
        javascript:alert(1)                         | http and https
        mailto:someone@example.com                  | http and https
        //example.com/scheme-relative               | http and https
        example.com                                 | http and https
        https://user:secret@example.com/            | credentials
        https://user@example.com/                   | credentials
        http:///no-host                             | host
        http://exa mple.com/                        | valid absolute URI
        """)
    void rejectsUnsafeOrInvalidUrls(String url, String reason) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(UrlRejectedException.class)
                .hasMessageContaining(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://[fe80::1%25eth0]/",
        "http://[fe80::1%25en0]:8080/admin",
        "http://[fe80::1%251]/",
        "http://[::1%25lo]/",
        "http://[::ffff:0:127.0.0.1]/",
        "http://[::ffff:0:a00:1]/",
        "http://[2002:7f00:1::]/",
        "http://[2002:a9fe:a9fe::1]/",
        "http://[64:ff9b:1::a00:1]/",
        "http://[64:ff9b:1::808:808]/"
    })
    void rejectsIpv6LiteralsWithAZoneIdOrAnEmbeddedNonPublicAddress(String url) {
        // A zone ID (RFC 6874) made the literal unparsable, so the URL passed as a public host name; IPv4-translated,
        // 6to4 and local-use NAT64 addresses reach the embedded (or a local) IPv4 network.
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(UrlRejectedException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void rejectsOctalEncodedLoopbackHost() {
        // 0177.0.0.1 is 127.0.0.1 for inet_aton-style parsers (browsers, curl): never accepted.
        assertThatThrownBy(() -> validator.validate("http://0177.0.0.1/"))
                .isInstanceOf(UrlRejectedException.class);
    }

    @Test
    void rejectsUrlsLongerThan2048Characters() {
        String base = "https://example.com/";
        String tooLong = base + "a".repeat(UrlValidator.MAX_LENGTH - base.length() + 1);

        assertThatThrownBy(() -> validator.validate(tooLong))
                .isInstanceOf(UrlRejectedException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void acceptsUrlOfExactly2048Characters() {
        String base = "https://example.com/";
        String maxLength = base + "a".repeat(UrlValidator.MAX_LENGTH - base.length());

        assertThat(validator.validate(maxLength).toString()).hasSize(UrlValidator.MAX_LENGTH);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsBlankUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(UrlRejectedException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void allowsPrivateTargetsWhenBlockingIsDisabled() {
        UrlValidator permissive = TestProperties.urlValidator(TestProperties.create(false, false));

        assertThat(permissive.validate("http://127.0.0.1:8080/")).hasHost("127.0.0.1");
        assertThat(permissive.validate("http://localhost/")).hasHost("localhost");
        assertThatThrownBy(() -> permissive.validate("ftp://127.0.0.1/"))
                .isInstanceOf(UrlRejectedException.class);
        assertThatThrownBy(() -> permissive.validate("http://user:pw@localhost/"))
                .isInstanceOf(UrlRejectedException.class);
    }

    /** {@code shortener.resolve-targets=true}: host names are resolved through a fake resolver. */
    @Nested
    class ResolveTargets {

        private final FakeHostResolver dns = new FakeHostResolver();
        private final MutableClock clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        private final UrlValidator resolving = new UrlValidator(TestProperties.withResolveTargets(true, true), dns,
                clock);

        @Test
        void acceptsNamesResolvingOnlyToPublicAddresses() {
            dns.answer("example.com", "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946");

            assertThat(resolving.validate("https://example.com/path")).hasHost("example.com");
            assertThat(dns.lookups("example.com")).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"10.0.0.7", "127.0.0.1", "169.254.169.254", "100.64.1.1", "::1", "fd00::1",
            "::ffff:192.168.1.1"})
        void rejectsNamesResolvingToANonPublicAddress(String address) {
            dns.answer("sneaky.example.com", "93.184.216.34", address);

            assertThatThrownBy(() -> resolving.validate("https://sneaky.example.com/"))
                    .isInstanceOf(UrlRejectedException.class)
                    .hasMessage("Target host 'sneaky.example.com' resolves to a private, loopback or otherwise "
                            + "non-public address.")
                    .hasMessageNotContaining(address);
        }

        @Test
        void rejectsNamesThatDoNotResolve() {
            dns.unknownByDefault();

            assertThatThrownBy(() -> resolving.validate("https://no-such-host.example/"))
                    .isInstanceOf(UrlRejectedException.class)
                    .hasMessage("Target host 'no-such-host.example' could not be resolved.");
        }

        @Test
        void rejectsNamesThatDoNotResolveInTime() {
            dns.timeout("slow.example.com");

            assertThatThrownBy(() -> resolving.validate("https://slow.example.com/"))
                    .isInstanceOf(UrlRejectedException.class)
                    .hasMessage("Target host 'slow.example.com' could not be resolved within 2000 ms; please retry.");
        }

        @Test
        void cachesVerdictsPerHostForSixtySeconds() {
            dns.answer("cached.example.com", "93.184.216.34");
            dns.answer("internal.example.com", "10.1.2.3");

            resolving.validate("https://cached.example.com/a");
            resolving.validate("https://CACHED.example.com./b");
            assertThatThrownBy(() -> resolving.validate("https://internal.example.com/"))
                    .isInstanceOf(UrlRejectedException.class);
            assertThatThrownBy(() -> resolving.validate("https://internal.example.com/again"))
                    .isInstanceOf(UrlRejectedException.class);
            assertThat(dns.lookups("cached.example.com")).isEqualTo(1);
            assertThat(dns.lookups("internal.example.com")).isEqualTo(1);

            clock.advance(Duration.ofSeconds(61));
            dns.answer("internal.example.com", "93.184.216.34"); // the answer changed meanwhile
            resolving.validate("https://cached.example.com/c");

            assertThat(dns.lookups("cached.example.com")).isEqualTo(2);
            assertThat(resolving.validate("https://internal.example.com/")).hasHost("internal.example.com");
        }

        @Test
        void doesNotCacheResolutionFailures() {
            dns.timeout("flaky.example.com");
            assertThatThrownBy(() -> resolving.validate("https://flaky.example.com/"))
                    .isInstanceOf(UrlRejectedException.class);

            dns.answer("flaky.example.com", "93.184.216.34");

            assertThat(resolving.validate("https://flaky.example.com/")).hasHost("flaky.example.com");
            assertThat(dns.lookups("flaky.example.com")).isEqualTo(2);
        }

        @Test
        void neverResolvesIpLiteralsOrBlockedNames() {
            UrlValidator strict = new UrlValidator(TestProperties.withResolveTargets(true, true),
                    TestProperties.NO_DNS, clock);

            assertThat(strict.validate("https://93.184.216.34/")).hasHost("93.184.216.34");
            assertThat(strict.validate("https://[2606:2800:220:1:248:1893:25c8:1946]/")).isNotNull();
            assertThatThrownBy(() -> strict.validate("http://10.0.0.1/")).isInstanceOf(UrlRejectedException.class);
            assertThatThrownBy(() -> strict.validate("http://printer.local/"))
                    .isInstanceOf(UrlRejectedException.class);
        }

        @Test
        void resolvesNothingWhenTheCheckOrPrivateTargetBlockingIsOff() {
            UrlValidator off = new UrlValidator(TestProperties.withResolveTargets(true, false), TestProperties.NO_DNS,
                    clock);
            UrlValidator permissive = new UrlValidator(TestProperties.withResolveTargets(false, true),
                    TestProperties.NO_DNS, clock);

            assertThat(off.validate("https://anything.example.com/")).hasHost("anything.example.com");
            assertThat(permissive.validate("https://anything.example.com/")).hasHost("anything.example.com");
        }
    }
}
