package com.agenticsdlc.shortener.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.service.ClientIpResolver;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the link-creation rate limiter when {@code shortener.rate-limit.enabled=true} (the default).
 *
 * <p>The filter is registered explicitly (not component-scanned) so that it is mapped to the create endpoint only
 * and is not pulled into MVC test slices.
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
        registration.addUrlPatterns(LinkController.BASE_PATH);
        // After the observation filter (HIGHEST_PRECEDENCE + 1) so rejected requests still show up in HTTP metrics.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
