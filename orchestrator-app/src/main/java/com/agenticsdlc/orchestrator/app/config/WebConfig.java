package com.agenticsdlc.orchestrator.app.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;

/**
 * Spring MVC customization: registers the {@code X-Operator-Id} argument resolver. The operator console is
 * served by Spring Boot's static resource handling from {@code classpath:/static/} ({@code index.html} is the
 * welcome page at {@code /}).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final OperatorIdentityResolver operatorIdentityResolver;

    /**
     * Creates the configuration.
     *
     * @param operatorIdentityResolver the operator identity resolver
     */
    public WebConfig(OperatorIdentityResolver operatorIdentityResolver) {
        this.operatorIdentityResolver = operatorIdentityResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(operatorIdentityResolver);
    }
}
