package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

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
}
