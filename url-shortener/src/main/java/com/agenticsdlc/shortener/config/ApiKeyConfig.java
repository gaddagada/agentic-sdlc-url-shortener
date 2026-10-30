package com.agenticsdlc.shortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the {@link ApiKeyFilter} protecting the management API.
 *
 * <p>Without {@code shortener.security.api-key} the API is open (local development, demos) and a single warning is
 * logged at startup; the filter is still registered but lets every request through. The key should be a long random
 * secret (at least 32 characters, e.g. {@code openssl rand -base64 32}); a shorter key is accepted with a warning.
 *
 * <p>Registered explicitly (not component-scanned) so that MVC test slices stay unauthenticated, and ordered after the
 * security-headers filter (so {@code 401} responses carry those headers) and before the rate-limit filter (so
 * unauthenticated requests never consume a client's tokens).
 */
@Configuration(proxyBeanMethods = false)
class ApiKeyConfig {

    static final int RECOMMENDED_MIN_KEY_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(ApiKeyConfig.class);

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ShortenerProperties properties, ObjectMapper objectMapper) {
        ShortenerProperties.Security security = properties.security();
        if (!security.apiKeyConfigured()) {
            log.warn("shortener.security.api-key is not set: the management API (/api/v1/links/**) is open to "
                    + "anyone. Set it (environment variable SHORTENER_SECURITY_APIKEY) outside local development.");
        } else if (security.apiKey().strip().length() < RECOMMENDED_MIN_KEY_LENGTH) {
            log.warn("shortener.security.api-key is shorter than {} characters; use a long random secret",
                    RECOMMENDED_MIN_KEY_LENGTH);
        }
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyFilter(security.apiKey(), objectMapper));
        registration.setName("apiKeyFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 8);
        return registration;
    }
}
