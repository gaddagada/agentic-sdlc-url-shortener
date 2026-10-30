package com.agenticsdlc.orchestrator.app.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hides the static operator console when {@code sdlc.console.enabled=false} (API-only deployments). The console
 * is enabled by default and then needs no configuration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sdlc.console", name = "enabled", havingValue = "false")
public class ConsoleConfig {

    /**
     * Registers a filter answering 404 for the console resources.
     *
     * @return the registration
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> consoleDisabledFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    }
                });
        registration.addUrlPatterns("/", "/index.html", "/app.js", "/styles.css", "/js/*");
        registration.setName("consoleDisabledFilter");
        return registration;
    }
}
