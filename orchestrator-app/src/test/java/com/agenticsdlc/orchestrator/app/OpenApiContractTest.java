package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the committed contract {@code docs/api/orchestrator.openapi.yaml} in sync with the controllers: every
 * {@code /api/v1} operation the controllers expose must be in the committed file (and in the document springdoc
 * generates), and the committed file must not describe operations that no longer exist. Regenerate the file from
 * a running application with {@code curl http://localhost:8090/v3/api-docs.yaml}, or without one with
 * {@code ./mvnw -pl orchestrator-app test -Dtest=OpenApiContractTest -Dopenapi.regenerate=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "docs", "api", "orchestrator.openapi.yaml");
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private ApplicationContext context;

    @Test
    void committedContractListsEveryOperationTheControllersExpose() throws IOException {
        SortedSet<String> exposed = exposedOperations();
        assertThat(exposed).hasSize(27).contains("POST /api/v1/runs", "GET /api/v1/runs/{id}/lineage",
                "POST /api/v1/runs/{id}/stages/{stageId}/approve", "GET /api/v1/providers",
                "GET /api/v1/runs/{id}/audit/integrity", "GET /api/v1/runs/events", "GET /api/v1/runs/{id}/events",
                "GET /api/v1/runs/{id}/evaluation", "GET /api/v1/policy", "POST /api/v1/evaluation/suite/run");

        assertThat(operations(committed())).as("committed contract").isEqualTo(exposed);
        assertThat(operations(generated())).as("springdoc document").isEqualTo(exposed);
    }

    @Test
    void mutatingOperationsDocumentTheOperatorHeader() throws IOException {
        Map<String, Object> paths = asMap(committed().get("paths"));
        paths.forEach((path, item) -> asMap(item).forEach((method, operation) -> {
            if ("post".equals(method)) {
                List<Object> parameters = asList(asMap(operation).get("parameters"));
                assertThat(parameters).as("POST %s", path).anySatisfy(p ->
                        assertThat(asMap(p)).containsEntry("in", "header").containsEntry("name", "X-Operator-Id"));
            }
        }));
    }

    @Test
    void createOperationsDocument201() throws IOException {
        for (Map<String, Object> document : List.of(committed(), generated())) {
            for (String path : List.of("/api/v1/runs", "/api/v1/scenarios/{key}/run")) {
                Map<String, Object> responses = asMap(operation(document, path, "post").get("responses"));
                assertThat(responses).as("POST %s", path).containsKey("201").doesNotContainKey("200");
            }
        }
    }

    @Test
    void governanceOperationsDocumentTheirProblemDetails() throws IOException {
        Map<String, Object> document = committed();
        for (String path : List.of("/api/v1/runs/{id}/stages/{stageId}/approve",
                "/api/v1/runs/{id}/stages/{stageId}/reject", "/api/v1/runs/{id}/clarify", "/api/v1/runs/{id}/replan",
                "/api/v1/runs/{id}/stop", "/api/v1/runs/{id}/resume")) {
            Map<String, Object> responses = asMap(operation(document, path, "post").get("responses"));
            for (String status : List.of("400", "404", "409")) {
                Map<String, Object> content = asMap(asMap(responses.get(status)).get("content"));
                assertThat(content).as("POST %s %s", path, status).containsKey("application/problem+json");
                assertThat(asMap(asMap(content.get("application/problem+json")).get("schema")))
                        .containsEntry("$ref", "#/components/schemas/ProblemDetail");
            }
        }
        assertThat(asMap(asMap(document.get("components")).get("schemas"))).containsKey("ProblemDetail");
    }

    @Test
    void schemaNamesAreUniqueAndTheReviewFieldsAreDocumented() throws IOException {
        Map<String, Object> schemas = asMap(asMap(committed().get("components")).get("schemas"));

        assertThat(schemas).containsKeys("GraphNodeView", "GraphEdgeView", "LineageNodeView", "LineageEdgeView",
                "RunScenarioRequest").doesNotContainKeys("NodeView", "EdgeView");
        assertThat(asMap(asMap(schemas.get("PendingActionView")).get("properties")))
                .containsKeys("approvalId", "reviewVersion", "reviewHash");
        assertThat(asMap(asMap(schemas.get("ApproveRequest")).get("properties"))).containsKey("approvalId");
        assertThat(asMap(asMap(schemas.get("RejectRequest")).get("properties"))).containsKey("approvalId");
        assertThat(asMap(asMap(schemas.get("ApprovalView")).get("properties"))).containsKey("artifactHash");
        assertThat(asMap(asMap(schemas.get("StageView")).get("properties"))).containsKey("acceptedVersion");
    }

    @Test
    void theApiKeySchemeAppliesToEveryOperation() throws IOException {
        Map<String, Object> document = committed();

        assertThat(asList(document.get("security"))).anySatisfy(requirement ->
                assertThat(asMap(requirement)).containsKey("apiKey"));
        assertThat(asMap(asMap(asMap(document.get("components")).get("securitySchemes")).get("apiKey")))
                .containsEntry("name", "X-Api-Key").containsEntry("in", "header");
    }

    @Test
    void securitySchemesRequiredRolesAndAuthenticationErrorsAreDocumented() throws IOException {
        for (Map<String, Object> document : List.of(committed(), generated())) {
            Map<String, Object> schemes = asMap(asMap(document.get("components")).get("securitySchemes"));
            assertThat(asMap(schemes.get("bearerAuth"))).containsEntry("type", "http")
                    .containsEntry("scheme", "bearer").containsEntry("bearerFormat", "JWT");
            Map<String, Object> approve = operation(document, "/api/v1/runs/{id}/stages/{stageId}/approve", "post");
            assertThat(asList(approve.get("x-required-roles"))).containsExactly("APPROVER");
            assertThat(asMap(approve.get("responses"))).containsKeys("401", "403");
            assertThat(asList(operation(document, "/api/v1/runs", "post").get("x-required-roles")))
                    .containsExactly("OPERATOR");
            assertThat(asList(operation(document, "/api/v1/workspace/reindex", "post").get("x-required-roles")))
                    .containsExactly("ADMIN");
            assertThat(asList(operation(document, "/api/v1/runs", "get").get("x-required-roles")))
                    .containsExactly("OPERATOR", "APPROVER", "ADMIN");
        }
    }

    @Test
    void theAuditIntegrityAndChangeRecordSignatureAreDocumented() throws IOException {
        for (Map<String, Object> document : List.of(committed(), generated())) {
            Map<String, Object> integrity = operation(document, "/api/v1/runs/{id}/audit/integrity", "get");
            assertThat(integrity).containsEntry("operationId", "getAuditIntegrity");
            assertThat(asMap(integrity.get("responses"))).containsKeys("200", "404");
            assertThat(asList(integrity.get("x-required-roles"))).containsExactly("OPERATOR", "APPROVER", "ADMIN");
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            assertThat(asMap(asMap(schemas.get("AuditIntegrityView")).get("properties"))).containsKeys("valid",
                    "length", "brokenAtSeq", "expectedHash", "actualHash", "summary");
            assertThat(asMap(asMap(schemas.get("ArtifactView")).get("properties")))
                    .containsKeys("signature", "signatureVerified");
            assertThat(asMap(asMap(schemas.get("SignatureView")).get("properties")))
                    .containsKeys("algorithm", "keyId", "value", "signedAt");
        }
    }

    @Test
    void theEventStreamsArePlainTextEventStreamsWithTheirProblems() throws IOException {
        for (Map<String, Object> document : List.of(committed(), generated())) {
            for (String path : List.of("/api/v1/runs/{id}/events", "/api/v1/runs/events")) {
                Map<String, Object> responses = asMap(operation(document, path, "get").get("responses"));
                assertThat(asMap(asMap(responses.get("200")).get("content"))).as("GET %s", path)
                        .containsOnlyKeys("text/event-stream");
                assertThat(responses).as("GET %s", path).containsKey("503");
                assertThat(asList(operation(document, path, "get").get("x-required-roles")))
                        .containsExactly("OPERATOR", "APPROVER", "ADMIN");
            }
            assertThat(asMap(operation(document, "/api/v1/runs/{id}/events", "get").get("responses")))
                    .containsKey("404");
            assertThat(asList(operation(document, "/api/v1/runs/{id}/events", "get").get("parameters")))
                    .anySatisfy(p -> assertThat(asMap(p)).containsEntry("in", "header")
                            .containsEntry("name", "Last-Event-ID"));
        }
    }

    @Test
    void theRunsListIsAPagedFilterableEnvelope() throws IOException {
        for (Map<String, Object> document : List.of(committed(), generated())) {
            Map<String, Object> list = operation(document, "/api/v1/runs", "get");
            assertThat(asList(list.get("parameters"))).extracting(p -> asMap(p).get("name"))
                    .containsExactlyInAnyOrder("page", "size", "status", "scenario", "startedBy", "sort");
            Map<String, Object> ok = asMap(asMap(list.get("responses")).get("200"));
            assertThat(asMap(asMap(asMap(ok.get("content")).values().iterator().next()).get("schema")))
                    .containsEntry("$ref", "#/components/schemas/RunPageView");
            assertThat(asMap(list.get("responses"))).containsKey("400");
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            assertThat(asMap(asMap(schemas.get("RunPageView")).get("properties"))).containsKeys("items", "page",
                    "size", "total", "totalPages", "sort");
            assertThat(asMap(asMap(schemas.get("RunSummaryView")).get("properties"))).containsKeys("endedAt",
                    "autonomy", "workspace", "startedBy", "riskLevel", "nodeId", "pendingActions", "progress");
        }
    }

    @Test
    void costCachePolicyEvaluationAndDependenciesAreDocumented() throws IOException {
        for (Map<String, Object> document : List.of(committed(), generated())) {
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            assertThat(asMap(asMap(schemas.get("CountersView")).get("properties"))).containsKeys(
                    "llmCacheReadTokens", "llmCacheCreationTokens", "llmCostUsd", "llmUnpricedCalls");
            assertThat(asMap(asMap(schemas.get("ProvenanceView")).get("properties"))).containsKeys(
                    "cacheReadTokens", "cacheCreationTokens", "costMicroUsd");
            assertThat(asMap(asMap(schemas.get("Llm")).get("properties"))).containsKeys("cacheReadTokens",
                    "cacheCreationTokens", "estimatedCostUsd", "unpricedCalls");
            assertThat(asMap(asMap(schemas.get("ArtifactView")).get("properties"))).containsKey("dependencies");
            assertThat(asMap(asMap(schemas.get("DependencyView")).get("properties"))).containsKeys("coordinate",
                    "flags");
            assertThat(asMap(asMap(schemas.get("PolicyView")).get("properties"))).containsKeys("builtInRules",
                    "activeRuleIds", "pack");
            assertThat(asMap(asMap(schemas.get("PackView")).get("properties"))).containsKeys("name", "version",
                    "hash", "disabledRules", "rules");
            assertThat(asMap(asMap(schemas.get("EvaluationReportView")).get("properties"))).containsKeys("runId",
                    "overall", "stages", "notes");
            assertThat(asMap(asMap(schemas.get("SuiteResultView")).get("properties"))).containsKeys("suite",
                    "passed", "cases", "markdown");

            Map<String, Object> evaluation = operation(document, "/api/v1/runs/{id}/evaluation", "get");
            assertThat(asMap(asMap(asMap(evaluation.get("responses")).get("200")).get("content")))
                    .containsKeys("application/json", "text/markdown");
            Map<String, Object> suite = operation(document, "/api/v1/evaluation/suite/run", "post");
            assertThat(asList(suite.get("x-required-roles"))).containsExactly("ADMIN");
            assertThat(asMap(suite.get("responses"))).containsKeys("200", "400", "404", "409");
            assertThat(asList(operation(document, "/api/v1/policy", "get").get("x-required-roles")))
                    .containsExactly("OPERATOR", "APPROVER", "ADMIN");
        }
    }

    @Test
    void providersDocumentTheirCircuitBreakerState() throws IOException {
        Map<String, Object> schemas = asMap(asMap(committed().get("components")).get("schemas"));

        assertThat(asMap(asMap(schemas.get("ProviderView")).get("properties"))).containsKey("circuit");
        assertThat(asMap(asMap(schemas.get("ProviderCircuitView")).get("properties"))).containsKeys("providerId",
                "state", "consecutiveFailures", "openedAt", "retryAt", "lastFailure");
    }

    /**
     * Rewrites the committed contract from the generated document (opt-in with {@code -Dopenapi.regenerate=true}).
     */
    @Test
    @EnabledIfSystemProperty(named = "openapi.regenerate", matches = "true")
    void regenerateCommittedContract() throws IOException {
        MvcTestResult result = mvc.get().uri("http://localhost:8090/v3/api-docs.yaml").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        String yaml = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        Files.writeString(CONTRACT, yaml.endsWith("\n") ? yaml : yaml + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void committedContractIsOpenApi3() throws IOException {
        assertThat(committed().get("openapi").toString()).startsWith("3.");
    }

    @Test
    void yamlDocumentIsServed() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs.yaml").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyText().contains("/api/v1/runs");
    }

    private SortedSet<String> exposedOperations() {
        SortedSet<String> operations = new TreeSet<>();
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (path.startsWith("/api/v1/")) {
                    info.getMethodsCondition().getMethods()
                            .forEach(m -> operations.add(m.name() + " " + path));
                }
            }
        }
        return operations;
    }

    private static Map<String, Object> operation(Map<String, Object> document, String path, String method) {
        Map<String, Object> operation = asMap(asMap(asMap(document.get("paths")).get(path)).get(method));
        assertThat(operation).as("%s %s", method, path).isNotEmpty();
        return operation;
    }

    private Map<String, Object> generated() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return asMap(json.readValue(body, Map.class));
    }

    private static Map<String, Object> committed() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONTRACT)) {
            return asMap(new Yaml().load(reader));
        }
    }

    private static SortedSet<String> operations(Map<String, Object> document) {
        SortedSet<String> operations = new TreeSet<>();
        asMap(document.get("paths")).forEach((path, item) -> asMap(item).keySet().forEach(method -> {
            if (HTTP_METHODS.contains(method)) {
                operations.add(method.toUpperCase(Locale.ROOT) + " " + path);
            }
        }));
        return operations;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }
}
