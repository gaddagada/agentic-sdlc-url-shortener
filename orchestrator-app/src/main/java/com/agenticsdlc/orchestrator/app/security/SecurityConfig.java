package com.agenticsdlc.orchestrator.app.security;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentitySource;
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties.Security.Mode;

import tools.jackson.databind.json.JsonMapper;

/**
 * Security wiring of the control plane for the three modes of {@code sdlc.security.mode}:
 *
 * <ul>
 *   <li>{@code open} (default without a key): no authentication; Spring Security's anonymous principal holds every
 *       role, so the authorization rules never deny. The operator id comes from {@code X-Operator-Id}.</li>
 *   <li>{@code api-key} (default when {@code sdlc.security.api-key} is set): the {@link ApiKeyAuthFilter} servlet
 *       filter, unchanged, guards the normalized {@code /api/**} paths in front of Spring Security; a request that
 *       passes it holds every role. The operator id comes from {@code X-Operator-Id}.</li>
 *   <li>{@code oidc}: an OAuth 2.0 resource server inside the Spring Security filter chain verifies bearer JWTs
 *       ({@link OidcTokens}); roles come from the roles claim ({@link OidcRoleMapper}) and the operator id from the
 *       identity claim ({@link SecurityContextOperatorIdentity}).</li>
 * </ul>
 *
 * <p>In every mode one stateless {@link SecurityFilterChain} enforces {@link ApiAuthorizationRules} and renders
 * 401/403 as Problem Details ({@link SecurityProblemHandlers}); CSRF protection, sessions, form login, HTTP basic,
 * logout and the request cache are off because no mode authenticates with cookies. Spring Security's response
 * headers are off as well: the {@link SecurityHeadersFilter} runs first in every mode and owns them. The filter
 * order is: security headers, API key ({@code api-key} mode only), Spring Security, then the application.</p>
 *
 * <p>The API key filter stays a plain servlet filter in front of the chain (rather than being rewritten as a Spring
 * Security filter) because that is the lower-risk option: its path normalization, constant-time comparison, 401
 * body and tests are unchanged, and it answers before Spring Security's firewall sees the request, exactly as
 * before.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** Property selecting the mode. */
    public static final String MODE_PROPERTY = "sdlc.security.mode";

    /** Property holding the API key. */
    public static final String API_KEY_PROPERTY = "sdlc.security.api-key";

    /**
     * Registers the security headers filter first in the chain, for requests and error dispatches.
     *
     * @return the registration
     */
    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.setName("securityHeadersFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Registers the API-key filter for every path in {@code api-key} mode; it filters only requests whose
     * normalized path is under {@code /api}.
     *
     * @param properties configuration
     * @param mapper JSON mapper
     * @return the registration
     */
    @Bean
    @Conditional(ApiKeyMode.class)
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(OrchestratorProperties properties,
                                                                           JsonMapper mapper) {
        SecurityPostureValidator.requireUsable(properties.security());
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyAuthFilter(properties.security().apiKey(), mapper));
        registration.setName("apiKeyAuthFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * Returns the JWT decoder of {@code oidc} mode.
     *
     * @param properties configuration
     * @return the decoder
     */
    @Bean
    @Conditional(OidcMode.class)
    JwtDecoder oidcJwtDecoder(OrchestratorProperties properties) {
        SecurityPostureValidator.requireUsable(properties.security());
        return OidcTokens.decoder(properties.security().oidc());
    }

    /**
     * Builds the filter chain of the configured mode.
     *
     * @param http the HTTP security builder
     * @param properties configuration
     * @param mapper JSON mapper for Problem Details
     * @param jwtDecoder the JWT decoder ({@code oidc} mode only)
     * @return the filter chain
     * @throws Exception when Spring Security cannot build the chain
     */
    @Bean
    SecurityFilterChain controlPlaneSecurityFilterChain(HttpSecurity http, OrchestratorProperties properties,
                                                        JsonMapper mapper, ObjectProvider<JwtDecoder> jwtDecoder)
            throws Exception {
        OrchestratorProperties.Security security = properties.security();
        Mode mode = security.effectiveMode();
        SecurityProblemHandlers problems = new SecurityProblemHandlers(mapper, mode == Mode.OIDC);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .authorizeHttpRequests(auth -> ApiAuthorizationRules.configure(auth, security.publicDocs()));
        if (mode == Mode.OIDC) {
            JwtDecoder decoder = jwtDecoder.getObject();
            http.oauth2ResourceServer(server -> server
                    .jwt(jwt -> jwt.decoder(decoder)
                            .jwtAuthenticationConverter(OidcTokens.authenticationConverter(security.oidc())))
                    .authenticationEntryPoint(problems)
                    .accessDeniedHandler(problems));
        } else {
            // open and api-key: whoever gets past the (optional) key filter acts in every role
            http.anonymous(anonymous -> anonymous
                    .principal(mode == Mode.API_KEY ? "api-key-client" : "anonymous-operator")
                    .authorities(ControlPlaneRole.allAuthorities().toArray(String[]::new)));
        }
        return http.build();
    }

    /**
     * Returns the source of verified operator identities (the token identity in {@code oidc} mode, none
     * otherwise).
     *
     * @param properties configuration
     * @return the source
     */
    @Bean
    OperatorIdentitySource operatorIdentitySource(OrchestratorProperties properties) {
        return new SecurityContextOperatorIdentity(properties.security().effectiveMode() == Mode.OIDC);
    }

    /**
     * Resolves the effective mode from the raw properties (conditions run before the properties are bound).
     *
     * @param environment the environment
     * @return the effective mode
     */
    static Mode effectiveMode(Environment environment) {
        return Mode.resolve(Mode.parse(environment.getProperty(MODE_PROPERTY)),
                environment.getProperty(API_KEY_PROPERTY, ""));
    }

    /**
     * Matches when the effective mode is a given mode.
     */
    abstract static class ModeCondition extends SpringBootCondition {

        private final Mode mode;

        ModeCondition(Mode mode) {
            this.mode = mode;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Mode effective = effectiveMode(context.getEnvironment());
            String message = "security mode is " + effective.propertyValue();
            return effective == mode ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
        }
    }

    /**
     * Matches in {@code api-key} mode.
     */
    static final class ApiKeyMode extends ModeCondition {

        ApiKeyMode() {
            super(Mode.API_KEY);
        }
    }

    /**
     * Matches in {@code oidc} mode.
     */
    static final class OidcMode extends ModeCondition {

        OidcMode() {
            super(Mode.OIDC);
        }
    }
}
