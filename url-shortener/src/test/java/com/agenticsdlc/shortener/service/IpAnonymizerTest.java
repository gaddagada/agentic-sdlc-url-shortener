package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.support.TestProperties;

class IpAnonymizerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 15);

    private final IpAnonymizer anonymizer = new IpAnonymizer(TestProperties.defaults());

    @Test
    void producesStableHexSha256WithoutTheRawIp() {
        String hash = anonymizer.hash("203.0.113.9", DAY);

        assertThat(hash).matches("^[0-9a-f]{64}$").doesNotContain("203.0.113.9");
        assertThat(anonymizer.hash("203.0.113.9", DAY)).isEqualTo(hash);
        // SHA-256("203.0.113.9:test-salt:2026-01-15")
        assertThat(hash).isEqualTo(Sha256.hex("203.0.113.9:test-salt:2026-01-15"));
    }

    @Test
    void rotatesDaily() {
        assertThat(anonymizer.hash("203.0.113.9", DAY)).isNotEqualTo(anonymizer.hash("203.0.113.9", DAY.plusDays(1)));
    }

    @Test
    void dependsOnTheSecretSalt() {
        ShortenerProperties otherSalt = new ShortenerProperties("https://sho.rt", 7, Duration.ofDays(365), true, false,
                new ShortenerProperties.RateLimit(true, 20, 20), new ShortenerProperties.Analytics("other-salt"));

        assertThat(new IpAnonymizer(otherSalt).hash("203.0.113.9", DAY))
                .isNotEqualTo(anonymizer.hash("203.0.113.9", DAY));
    }

    @Test
    void distinguishesVisitors() {
        assertThat(anonymizer.hash("203.0.113.9", DAY)).isNotEqualTo(anonymizer.hash("203.0.113.10", DAY));
    }
}
