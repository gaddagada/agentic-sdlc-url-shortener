package com.agenticsdlc.orchestrator.app.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

/**
 * Brings the real security configuration (filter chain, authorization rules, operator identity source) into
 * {@code @WebMvcTest} slices, which do not scan configuration classes. Without overriding properties the slice runs
 * in {@code open} mode, as the application does by default.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(OrchestratorProperties.class)
public class SecuritySliceConfiguration {
}
