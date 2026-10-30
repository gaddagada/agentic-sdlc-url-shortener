package com.agenticsdlc.orchestrator.app.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

import com.agenticsdlc.orchestrator.app.api.OperatorId;
import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.app.api.ProblemResponses;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * OpenAPI metadata for the springdoc-generated document ({@code /v3/api-docs}, UI at {@code /swagger-ui.html}).
 * The committed contract is {@code docs/api/orchestrator.openapi.yaml}; {@code OpenApiContractTest} keeps both in
 * sync.
 *
 * <p>{@link OperatorId @OperatorId} parameters are resolved from a header by a custom argument resolver, so
 * springdoc is told to ignore them and every such operation documents the required {@code X-Operator-Id} header
 * instead. Operations annotated with {@link ProblemResponses @ProblemResponses} document their RFC 9457 error
 * responses with the {@code ProblemDetail} schema. The {@code X-Api-Key} scheme applies to every operation: it is
 * documented as required when {@code sdlc.security.api-key} is set and as optional otherwise.</p>
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "SDLC Orchestrator API",
        version = "v1",
        description = "Control plane of the agentic SDLC orchestrator: start runs, govern them (approve, reject, "
                + "clarify, re-plan, stop, resume) and inspect artifacts, lineage, audit trail and reliability "
                + "metrics. Every POST requires the X-Operator-Id header. Errors are RFC 9457 Problem Details.",
        license = @License(name = "Apache-2.0")))
@SecurityScheme(name = OpenApiConfig.API_KEY_SCHEME, type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Api-Key", description = "Required on /api/** when sdlc.security.api-key is configured; "
        + "not checked otherwise (local development mode)")
public class OpenApiConfig {

    /** Name of the API-key security scheme. */
    static final String API_KEY_SCHEME = "apiKey";

    /** Name of the Problem Detail schema in the components. */
    static final String PROBLEM_SCHEMA = "ProblemDetail";

    private static final String PROBLEM_VIOLATION_SCHEMA = "ProblemViolation";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final Map<Integer, String> PROBLEM_DESCRIPTIONS = Map.of(
            400, "Invalid request: validation failed (see errors), malformed JSON, or a missing, malformed or "
                    + "reserved X-Operator-Id",
            404, "Unknown run, scenario or artifact",
            409, "Refused in the current state: wrong run or stage state, stale approvalId, invalid command "
                    + "(exhausted budget, missing waiver, ...), four-eyes rule or unavailable agent provider",
            503, "The control plane is shutting down");

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
                                    + "trail; engine, system, deterministic and fallback:* are reserved)")
                            .schema(new StringSchema().pattern(OperatorIdentityResolver.PATTERN)));
                }
            }
            return operation;
        };
    }

    /**
     * Documents the Problem Detail error responses declared with {@link ProblemResponses @ProblemResponses}.
     *
     * @return the customizer
     */
    @Bean
    OperationCustomizer problemResponsesCustomizer() {
        return (operation, handlerMethod) -> {
            ProblemResponses problems = handlerMethod.getMethodAnnotation(ProblemResponses.class);
            if (problems == null) {
                return operation;
            }
            ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
            for (int status : problems.value()) {
                responses.addApiResponse(String.valueOf(status), new ApiResponse()
                        .description(PROBLEM_DESCRIPTIONS.getOrDefault(status, "Error (Problem Detail)"))
                        .content(new Content().addMediaType(PROBLEM_JSON,
                                new MediaType().schema(ref(PROBLEM_SCHEMA)))));
            }
            operation.setResponses(responses);
            return operation;
        };
    }

    /**
     * Adds the {@code ProblemDetail} schema and the global API-key security requirement.
     *
     * @param properties configuration
     * @return the customizer
     */
    @Bean
    OpenApiCustomizer problemSchemaAndSecurityCustomizer(OrchestratorProperties properties) {
        boolean keyRequired = properties.security().apiKeyEnabled();
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            components.addSchemas(PROBLEM_VIOLATION_SCHEMA, typed("object")
                    .description("One validation failure")
                    .addProperty("field", typed("string").description("Offending field, parameter or header"))
                    .addProperty("message", typed("string").description("Constraint message")));
            components.addSchemas(PROBLEM_SCHEMA, typed("object")
                    .description("RFC 9457 Problem Detail (application/problem+json); clients should branch on "
                            + "type, not on title or detail")
                    .addProperty("type", typed("string").format("uri").description("Problem type URI"))
                    .addProperty("title", typed("string").description("Short summary"))
                    .addProperty("status", typed("integer").format("int32").description("HTTP status"))
                    .addProperty("detail", typed("string").description("Explanation of this occurrence"))
                    .addProperty("instance", typed("string").format("uri").description("Request path"))
                    .addProperty("errors", typed("array").items(ref(PROBLEM_VIOLATION_SCHEMA))
                            .description("Validation failures (validation-error only)")));
            openApi.setComponents(components);
            SecurityRequirement apiKey = new SecurityRequirement().addList(API_KEY_SCHEME);
            // an empty requirement makes the key optional (it is not checked without sdlc.security.api-key)
            openApi.setSecurity(keyRequired ? List.of(apiKey) : List.of(apiKey, new SecurityRequirement()));
        };
    }

    private static Schema<Object> typed(String type) {
        Schema<Object> schema = new Schema<>();
        schema.setType(type);
        schema.setTypes(Set.of(type));
        return schema;
    }

    private static Schema<Object> ref(String schemaName) {
        return new Schema<>().$ref("#/components/schemas/" + schemaName);
    }
}
