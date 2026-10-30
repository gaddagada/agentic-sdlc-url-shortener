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
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties.Security.Mode;
import com.agenticsdlc.orchestrator.app.security.ApiAuthorizationRules;
import com.agenticsdlc.orchestrator.app.security.ControlPlaneRole;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
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
 * springdoc is told to ignore them and every such operation documents the {@code X-Operator-Id} header instead
 * (required in {@code open} and {@code api-key} mode, optional in {@code oidc} mode where the identity comes from
 * the token). Operations annotated with {@link ProblemResponses @ProblemResponses} document their RFC 9457 error
 * responses with the {@code ProblemDetail} schema, and every {@code /api} operation documents 401 and 403 and
 * names the roles it needs in the {@value #REQUIRED_ROLES_EXTENSION} extension. Two security schemes are
 * described: the {@code X-Api-Key} header ({@code api-key} mode) and bearer JWTs ({@code oidc} mode); the global
 * requirement lists the scheme of the running mode (both, optional, in {@code open} mode).</p>
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "SDLC Orchestrator API",
        version = "v1",
        description = "Control plane of the agentic SDLC orchestrator: start runs, govern them (approve, reject, "
                + "clarify, re-plan, stop, resume) and inspect artifacts, lineage, audit trail and reliability "
                + "metrics. Every POST names the operator: the X-Operator-Id header (open and api-key mode) or the "
                + "identity claim of the bearer token (oidc mode). Roles: OPERATOR starts and steers runs, APPROVER "
                + "decides gates, ADMIN re-indexes the workspace, any role reads (see x-required-roles). Errors are "
                + "RFC 9457 Problem Details.",
        license = @License(name = "Apache-2.0")))
@SecurityScheme(name = OpenApiConfig.API_KEY_SCHEME, type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-Api-Key", description = "Shared key required on /api/** in api-key mode "
        + "(sdlc.security.mode=api-key, or sdlc.security.api-key set without a mode); not checked in the other modes")
@SecurityScheme(name = OpenApiConfig.BEARER_SCHEME, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "OIDC access token required in oidc mode: RS256-signed by the configured "
        + "issuer, aud containing sdlc.security.oidc.audience; roles from sdlc.security.oidc.roles-claim, operator id "
        + "from sdlc.security.oidc.identity-claim")
public class OpenApiConfig {

    /** Name of the API-key security scheme. */
    static final String API_KEY_SCHEME = "apiKey";

    /** Name of the bearer-token security scheme. */
    static final String BEARER_SCHEME = "bearerAuth";

    /** Name of the Problem Detail schema in the components. */
    static final String PROBLEM_SCHEMA = "ProblemDetail";

    /** Operation extension listing the roles that may call the operation (any one suffices). */
    static final String REQUIRED_ROLES_EXTENSION = "x-required-roles";

    private static final String PROBLEM_VIOLATION_SCHEMA = "ProblemViolation";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final Map<Integer, String> PROBLEM_DESCRIPTIONS = Map.of(
            400, "Invalid request: validation failed (see errors), malformed JSON, a missing, malformed or "
                    + "reserved X-Operator-Id, or (oidc mode) an X-Operator-Id that differs from the token identity",
            401, "Not authenticated: X-Api-Key missing or wrong (api-key mode), bearer token missing, expired or "
                    + "invalid (oidc mode)",
            403, "Authenticated, but the caller does not hold a role in x-required-roles (oidc mode)",
            404, "Unknown run, scenario or artifact",
            409, "Refused in the current state: wrong run or stage state, stale approvalId, invalid command "
                    + "(exhausted budget, missing waiver, ...), four-eyes rule, unavailable agent provider, or the run "
                    + "is executing on another engine node (type run-leased-elsewhere, with holder and runId)",
            503, "The control plane is shutting down");

    /**
     * Documents the {@code X-Operator-Id} header on every operation that resolves the operator identity.
     *
     * @param properties configuration
     * @return the customizer
     */
    @Bean
    OperationCustomizer operatorIdHeaderCustomizer(OrchestratorProperties properties) {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(OperatorId.class);
        boolean tokenIdentity = properties.security().effectiveMode() == Mode.OIDC;
        return (operation, handlerMethod) -> {
            for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(OperatorId.class)) {
                    operation.addParametersItem(new HeaderParameter()
                            .name(OperatorIdentityResolver.HEADER)
                            .required(!tokenIdentity)
                            .description("Id of the human operator issuing the request (recorded in the audit "
                                    + "trail; engine, system, deterministic and fallback:* are reserved). Required "
                                    + "in open and api-key mode; optional in oidc mode, where the operator is the "
                                    + "token's identity claim and a different value is refused with 400")
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
            for (int status : problems.value()) {
                addProblemResponse(operation, status);
            }
            return operation;
        };
    }

    /**
     * Adds the {@code ProblemDetail} schema, the global security requirement of the running mode, and to every
     * {@code /api} operation its 401 and 403 responses and its required roles.
     *
     * @param properties configuration
     * @return the customizer
     */
    @Bean
    OpenApiCustomizer problemSchemaAndSecurityCustomizer(OrchestratorProperties properties) {
        Mode mode = properties.security().effectiveMode();
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
            SecurityRequirement bearer = new SecurityRequirement().addList(BEARER_SCHEME);
            openApi.setSecurity(switch (mode) {
                case API_KEY -> List.of(apiKey);
                case OIDC -> List.of(bearer);
                // an empty requirement makes both optional: open mode checks neither
                case OPEN -> List.of(apiKey, bearer, new SecurityRequirement());
            });
            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach(OpenApiConfig::documentAuthorization);
            }
        };
    }

    private static void documentAuthorization(String path, PathItem item) {
        if (!path.startsWith("/api/")) {
            return;
        }
        String concretePath = path.replaceAll("\\{[^/}]+}", "x");
        item.readOperationsMap().forEach((method, operation) -> {
            List<String> roles = ApiAuthorizationRules.allowedRoles(method.name(), concretePath).stream()
                    .map(ControlPlaneRole::name).toList();
            operation.addExtension(REQUIRED_ROLES_EXTENSION, roles);
            addProblemResponse(operation, 401);
            addProblemResponse(operation, 403);
            sortResponses(operation);
        });
    }

    /** Orders the responses by status code (they are added by several customizers). */
    private static void sortResponses(Operation operation) {
        ApiResponses sorted = new ApiResponses();
        operation.getResponses().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.addApiResponse(entry.getKey(), entry.getValue()));
        sorted.setExtensions(operation.getResponses().getExtensions());
        operation.setResponses(sorted);
    }

    private static void addProblemResponse(Operation operation, int status) {
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        responses.addApiResponse(String.valueOf(status), new ApiResponse()
                .description(PROBLEM_DESCRIPTIONS.getOrDefault(status, "Error (Problem Detail)"))
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(ref(PROBLEM_SCHEMA)))));
        operation.setResponses(responses);
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
