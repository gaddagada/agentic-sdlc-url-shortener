package com.agenticsdlc.orchestrator.app.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

import com.agenticsdlc.orchestrator.app.api.OperatorId;
import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;

/**
 * OpenAPI metadata for the springdoc-generated document ({@code /v3/api-docs}, UI at {@code /swagger-ui.html}).
 * The committed contract is {@code docs/api/orchestrator.openapi.yaml}; {@code OpenApiContractTest} keeps both in
 * sync.
 *
 * <p>{@link OperatorId @OperatorId} parameters are resolved from a header by a custom argument resolver, so
 * springdoc is told to ignore them and every such operation documents the required {@code X-Operator-Id} header
 * instead.</p>
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "SDLC Orchestrator API",
        version = "v1",
        description = "Control plane of the agentic SDLC orchestrator: start runs, govern them (approve, reject, "
                + "clarify, re-plan, stop, resume) and inspect artifacts, lineage, audit trail and reliability "
                + "metrics. Every POST requires the X-Operator-Id header. Errors are RFC 9457 Problem Details.",
        license = @License(name = "Apache-2.0")))
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Api-Key", description = "Required on /api/** only when sdlc.security.api-key is configured")
public class OpenApiConfig {

    /**
     * Documents the {@code X-Operator-Id} header on every operation that resolves the operator identity.
     *
     * @return the customizer
     */
    @Bean
    OperationCustomizer operatorIdHeaderCustomizer() {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(OperatorId.class);
        return (operation, handlerMethod) -> {
            for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(OperatorId.class)) {
                    operation.addParametersItem(new HeaderParameter()
                            .name(OperatorIdentityResolver.HEADER)
                            .required(true)
                            .description("Id of the human operator issuing the request (recorded in the audit "
                                    + "trail)")
                            .schema(new StringSchema().pattern(OperatorIdentityResolver.PATTERN)));
                }
            }
            return operation;
        };
    }
}
