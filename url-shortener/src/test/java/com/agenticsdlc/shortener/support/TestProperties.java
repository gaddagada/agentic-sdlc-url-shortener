package com.agenticsdlc.shortener.support;

import java.time.Duration;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.config.ShortenerProperties.Analytics;
import com.agenticsdlc.shortener.config.ShortenerProperties.RateLimit;
import com.agenticsdlc.shortener.config.ShortenerProperties.Recorder;

/**
 * {@link ShortenerProperties} fixtures for unit tests.
 */
public final class TestProperties {

    public static final String BASE_URL = "https://sho.rt";
    public static final Recorder DEFAULT_RECORDER = new Recorder(8, 10_000);

    private TestProperties() {
    }

    public static ShortenerProperties defaults() {
        return create(true, false);
    }

    public static ShortenerProperties create(boolean blockPrivateTargets, boolean trustForwardedHeaders) {
        return new ShortenerProperties(BASE_URL, 7, Duration.ofDays(365), blockPrivateTargets,
                trustForwardedHeaders, new RateLimit(true, 20, 20), analytics("test-salt", Duration.ofDays(90)));
    }

    /** Defaults with the given analytics settings. */
    public static ShortenerProperties withAnalytics(Analytics analytics) {
        return new ShortenerProperties(BASE_URL, 7, Duration.ofDays(365), true, false, new RateLimit(true, 20, 20),
                analytics);
    }

    /** Analytics settings with the default recorder sizing. */
    public static Analytics analytics(String ipSalt, Duration retention) {
        return new Analytics(ipSalt, retention, DEFAULT_RECORDER);
    }
}
