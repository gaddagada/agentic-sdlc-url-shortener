package com.agenticsdlc.shortener.support;

import java.time.Clock;
import java.time.Duration;

import com.agenticsdlc.shortener.config.RedisRateLimiter;
import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.config.ShortenerProperties.Analytics;
import com.agenticsdlc.shortener.config.ShortenerProperties.RateLimit;
import com.agenticsdlc.shortener.config.ShortenerProperties.Recorder;
import com.agenticsdlc.shortener.config.ShortenerProperties.Security;
import com.agenticsdlc.shortener.service.HostResolver;
import com.agenticsdlc.shortener.service.UrlValidator;

/**
 * {@link ShortenerProperties} fixtures for unit tests.
 */
public final class TestProperties {

    public static final String BASE_URL = "https://sho.rt";
    public static final Recorder DEFAULT_RECORDER = new Recorder(8, 10_000);
    public static final RateLimit DEFAULT_RATE_LIMIT = new RateLimit(true, 20, 20, RateLimit.Backend.MEMORY,
            RedisRateLimiter.DEFAULT_KEY_PREFIX);
    public static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(2);

    /** A resolver for validators that must never resolve anything. */
    public static final HostResolver NO_DNS = host -> {
        throw new AssertionError("unexpected DNS lookup of " + host);
    };

    private TestProperties() {
    }

    public static ShortenerProperties defaults() {
        return create(true, false);
    }

    public static ShortenerProperties create(boolean blockPrivateTargets, boolean trustForwardedHeaders) {
        return new ShortenerProperties(BASE_URL, 7, Duration.ofDays(365), blockPrivateTargets, false,
                DEFAULT_RESOLVE_TIMEOUT, trustForwardedHeaders, DEFAULT_RATE_LIMIT,
                analytics("test-salt", Duration.ofDays(90)), new Security(null));
    }

    /** Defaults with the given analytics settings. */
    public static ShortenerProperties withAnalytics(Analytics analytics) {
        return new ShortenerProperties(BASE_URL, 7, Duration.ofDays(365), true, false, DEFAULT_RESOLVE_TIMEOUT, false,
                DEFAULT_RATE_LIMIT, analytics, new Security(null));
    }

    /** Defaults with the resolve-time target check switched on or off. */
    public static ShortenerProperties withResolveTargets(boolean blockPrivateTargets, boolean resolveTargets) {
        return new ShortenerProperties(BASE_URL, 7, Duration.ofDays(365), blockPrivateTargets, resolveTargets,
                DEFAULT_RESOLVE_TIMEOUT, false, DEFAULT_RATE_LIMIT, analytics("test-salt", Duration.ofDays(90)),
                new Security(null));
    }

    /** Analytics settings with the default recorder sizing. */
    public static Analytics analytics(String ipSalt, Duration retention) {
        return new Analytics(ipSalt, retention, DEFAULT_RECORDER);
    }

    /** A validator for properties without the resolve-time check: it fails the test if it ever resolves a host. */
    public static UrlValidator urlValidator(ShortenerProperties properties) {
        return new UrlValidator(properties, NO_DNS, Clock.systemUTC());
    }
}
