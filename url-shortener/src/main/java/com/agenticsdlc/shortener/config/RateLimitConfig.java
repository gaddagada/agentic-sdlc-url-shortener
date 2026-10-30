package com.agenticsdlc.shortener.config;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.service.RateLimiter;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the link-creation {@link RateLimiter} and its servlet filter when {@code shortener.rate-limit.enabled=true}
 * (the default).
 *
 * <p>The backend is chosen with {@code shortener.rate-limit.backend}: {@code memory} (default) creates a per-node
 * {@link TokenBucketRateLimiter}; {@code redis} creates a {@link RedisRateLimiter} on the auto-configured
 * {@link StringRedisTemplate}. Spring Boot's Redis auto-configuration (connection factory, templates, health
 * indicator) is only active with {@code backend=redis}, see {@link RedisBackendAutoConfigurationFilter}.
 *
 * <p>Charging, one token per created link:
 * <ul>
 *   <li>{@link RateLimitFilter} charges one token for every {@code POST} under {@code /api/v1/links}, except the bulk
 *       endpoint;</li>
 *   <li>the bulk endpoint is charged by {@code BulkLinkService}, which takes as many tokens as the request has items
 *       once the body has been parsed and validated (the filter would have to buffer and parse the JSON body a second
 *       time to know the count).</li>
 * </ul>
 *
 * <p>The filter is registered explicitly (not component-scanned) so that it is not pulled into MVC test slices. It is
 * mapped to every request and decides itself, on the decoded and normalized path, which requests are limited
 * ({@link RateLimitFilter#shouldNotFilter}); a narrower container URL pattern could disagree with how Spring MVC
 * routes encoded or path-parameter spellings of the create endpoint.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "shortener.rate-limit.enabled", matchIfMissing = true)
class RateLimitConfig {

    static final String BACKEND_PROPERTY = "shortener.rate-limit.backend";

    @Bean
    @ConditionalOnProperty(name = BACKEND_PROPERTY, havingValue = "memory", matchIfMissing = true)
    TokenBucketRateLimiter linkCreationRateLimiter(ShortenerProperties properties, Clock clock) {
        ShortenerProperties.RateLimit rateLimit = properties.rateLimit();
        return new TokenBucketRateLimiter(rateLimit.capacity(), rateLimit.refillPerMinute(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = BACKEND_PROPERTY, havingValue = "redis")
    RedisRateLimiter redisLinkCreationRateLimiter(ShortenerProperties properties, StringRedisTemplate redis,
                                                  Clock clock, MeterRegistry meterRegistry) {
        ShortenerProperties.RateLimit rateLimit = properties.rateLimit();
        return new RedisRateLimiter(redis, rateLimit.redisKeyPrefix(), rateLimit.capacity(),
                rateLimit.refillPerMinute(), clock, meterRegistry);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter linkCreationRateLimiter,
                                                            ClientIpResolver clientIpResolver,
                                                            ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(linkCreationRateLimiter, clientIpResolver, objectMapper));
        registration.setName("rateLimitFilter");
        registration.addUrlPatterns("/*");
        // After the observation filter (HIGHEST_PRECEDENCE + 1) so rejected requests still show up in HTTP metrics,
        // and after the API key filter so unauthenticated requests never consume a client's tokens.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
