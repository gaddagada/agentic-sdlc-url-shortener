package com.agenticsdlc.orchestrator.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the {@link ApiKeyAuthFilter} for {@code /api/*} when {@code sdlc.security.api-key} is set; otherwise
 * logs a single WARN at startup that the API is unauthenticated (local development mode).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /** Property holding the API key. */
    static final String API_KEY_PROPERTY = "sdlc.security.api-key";

    /**
     * Creates the configuration and reports the authentication mode once.
     *
     * @param properties configuration
     */
    public SecurityConfig(OrchestratorProperties properties) {
        if (properties.security().apiKeyEnabled()) {
            LOG.info("API authentication enabled: /api/** requires the {} header", ApiKeyAuthFilter.HEADER);
        } else {
            LOG.warn("API authentication disabled: local development mode (set {} to enable)", API_KEY_PROPERTY);
        }
    }

    /**
     * Registers the API-key filter.
     *
     * @param properties configuration
     * @param mapper JSON mapper
     * @return the registration
     */
    @Bean
    @Conditional(ApiKeyConfigured.class)
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(OrchestratorProperties properties,
                                                                           JsonMapper mapper) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyAuthFilter(properties.security().apiKey(), mapper));
        registration.setName("apiKeyAuthFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * Matches when a non-blank API key is configured.
     */
    static final class ApiKeyConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty(API_KEY_PROPERTY, "");
            return key.isBlank() ? ConditionOutcome.noMatch(API_KEY_PROPERTY + " is blank")
                    : ConditionOutcome.match(API_KEY_PROPERTY + " is set");
        }
    }
}
