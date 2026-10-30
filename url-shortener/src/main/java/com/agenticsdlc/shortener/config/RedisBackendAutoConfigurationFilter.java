package com.agenticsdlc.shortener.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Keeps Spring Boot's Redis auto-configuration out of the application unless
 * {@code shortener.rate-limit.backend=redis}.
 *
 * <p>{@code spring-boot-starter-data-redis} is on the classpath for the optional Redis rate limiter. Left alone, Boot
 * would create a Lettuce connection factory, templates and a Redis health indicator in every deployment, and the
 * default (in-memory) deployment, which has no Redis, would report {@code DOWN} on {@code /actuator/health}. This
 * filter vetoes every auto-configuration of the {@code spring-boot-data-redis} module ({@value #REDIS_PACKAGE}*) for
 * the other backends; with {@code backend=redis} the full auto-configuration applies, including
 * {@code spring.data.redis.*} properties, SSL, sentinel/cluster support and the health indicator.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Unlike {@code spring.autoconfigure.exclude} it depends on
 * another property, and it also applies to test slices.
 */
public class RedisBackendAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    static final String REDIS_PACKAGE = "org.springframework.boot.data.redis.";

    private @Nullable Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(@Nullable String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean redisBackend = redisBackendSelected();
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            String candidate = autoConfigurationClasses[i];
            matches[i] = redisBackend || candidate == null || !candidate.startsWith(REDIS_PACKAGE);
        }
        return matches;
    }

    private boolean redisBackendSelected() {
        String backend = environment == null ? null : environment.getProperty(RateLimitConfig.BACKEND_PROPERTY);
        return backend != null && backend.strip().equalsIgnoreCase(ShortenerProperties.RateLimit.Backend.REDIS.name());
    }
}
