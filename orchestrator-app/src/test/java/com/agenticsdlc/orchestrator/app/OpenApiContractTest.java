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
 * a running application with {@code curl http://localhost:8090/v3/api-docs.yaml}.
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
        assertThat(exposed).hasSize(21).contains("POST /api/v1/runs", "GET /api/v1/runs/{id}/lineage",
                "POST /api/v1/runs/{id}/stages/{stageId}/approve", "GET /api/v1/providers");

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
