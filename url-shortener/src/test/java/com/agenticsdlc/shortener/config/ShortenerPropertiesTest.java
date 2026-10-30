package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.agenticsdlc.shortener.config.ShortenerProperties.Security;
import com.agenticsdlc.shortener.support.TestProperties;

class ShortenerPropertiesTest {

    private static final String SALT = "hmac-salt-that-must-never-be-logged";
    private static final String API_KEY = "api-key-that-must-never-be-logged-0123456789";

    @Test
    void toStringNeverRevealsTheSecrets() {
        ShortenerProperties properties = new ShortenerProperties(TestProperties.BASE_URL, 7, Duration.ofDays(365),
                true, false, TestProperties.DEFAULT_RESOLVE_TIMEOUT, false, TestProperties.DEFAULT_RATE_LIMIT,
                TestProperties.analytics(SALT, Duration.ofDays(90)), new Security(API_KEY));

        assertThat(properties.toString())
                .doesNotContain(SALT)
                .doesNotContain(API_KEY)
                .contains("ipSalt=<redacted>")
                .contains("apiKey=<redacted>")
                .contains("retention=PT2160H");
    }

    @Test
    void bindsTheTakeoverWindowsAndTheIpv6PrefixLengthWithTheirDefaults() {
        ShortenerProperties defaults = bind(Map.of("shortener.analytics.ip-salt", SALT));
        assertThat(defaults.idempotency()).isEqualTo(ShortenerProperties.Idempotency.defaults());
        assertThat(defaults.rateLimit().ipv6PrefixLength()).isEqualTo(64);
        assertThat(defaults.idempotency().takeoverWindow(0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.idempotency().takeoverWindow(100)).isEqualTo(Duration.ofSeconds(160));
        assertThat(defaults.idempotency().takeoverWindow(10_000)).as("capped").isEqualTo(Duration.ofMinutes(10));

        ShortenerProperties custom = bind(Map.of("shortener.analytics.ip-salt", SALT,
                "shortener.idempotency.pending-takeover-after", "30s",
                "shortener.idempotency.pending-takeover-per-item", "250ms",
                "shortener.rate-limit.ipv6-prefix-length", "56"));
        assertThat(custom.idempotency().takeoverWindow(4)).isEqualTo(Duration.ofSeconds(31));
        assertThat(custom.rateLimit().ipv6PrefixLength()).isEqualTo(56);
        assertThat(custom.rateLimit().capacity()).isEqualTo(20);
    }

    @Test
    void rejectsInvalidTakeoverWindows() {
        assertThatThrownBy(() -> new ShortenerProperties.Idempotency(Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending-takeover-after");
        assertThatThrownBy(() -> new ShortenerProperties.Idempotency(Duration.ofMinutes(11), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShortenerProperties.Idempotency(Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending-takeover-per-item");
    }

    private static ShortenerProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("shortener",
                ShortenerProperties.class);
    }
}
