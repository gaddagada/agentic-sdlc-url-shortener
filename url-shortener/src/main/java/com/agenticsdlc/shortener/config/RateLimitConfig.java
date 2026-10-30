package com.agenticsdlc.shortener.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.agenticsdlc.shortener.service.ClientIpResolver;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the link-creation rate limiter when {@code shortener.rate-limit.enabled=true} (the default).
 *
 * <p>The filter is registered explicitly (not component-scanned) so that it is not pulled into MVC test slices. It is
 * mapped to every request and decides itself, on the decoded and normalized path, which requests are limited
 * ({@link RateLimitFilter#shouldNotFilter}); a narrower container URL pattern could disagree with how Spring MVC
 * routes encoded or path-parameter spellings of the create endpoint.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "shortener.rate-limit.enabled", matchIfMissing = true)
class RateLimitConfig {

    @Bean
    TokenBucketRateLimiter linkCreationRateLimiter(ShortenerProperties properties, Clock clock) {
        ShortenerProperties.RateLimit rateLimit = properties.rateLimit();
        return new TokenBucketRateLimiter(rateLimit.capacity(), rateLimit.refillPerMinute(), clock);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(TokenBucketRateLimiter linkCreationRateLimiter,
                                                            ClientIpResolver clientIpResolver,
                                                            ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(linkCreationRateLimiter, clientIpResolver, objectMapper));
        registration.setName("rateLimitFilter");
        registration.addUrlPatterns("/*");
        // After the observation filter (HIGHEST_PRECEDENCE + 1) so rejected requests still show up in HTTP metrics.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
