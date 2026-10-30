package com.agenticsdlc.shortener.support;

import java.time.Duration;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.config.ShortenerProperties.Analytics;
import com.agenticsdlc.shortener.config.ShortenerProperties.RateLimit;

/**
 * {@link ShortenerProperties} fixtures for unit tests.
 */
public final class TestProperties {

    public static final String BASE_URL = "https://sho.rt";

    private TestProperties() {
    }

    public static ShortenerProperties defaults() {
        return create(true, false);
    }

    public static ShortenerProperties create(boolean blockPrivateTargets, boolean trustForwardedHeaders) {
        return new ShortenerProperties(BASE_URL, 7, Duration.ofDays(365), blockPrivateTargets,
                trustForwardedHeaders, new RateLimit(true, 20, 20), new Analytics("test-salt"));
    }
}
