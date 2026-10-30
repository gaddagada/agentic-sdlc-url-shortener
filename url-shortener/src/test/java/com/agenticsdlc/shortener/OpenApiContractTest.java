package com.agenticsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.yaml.snakeyaml.Yaml;

import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the hand-maintained contract {@code docs/api/url-shortener.openapi.yaml} in sync with the controllers by
 * comparing it with the document springdoc generates at runtime: every operation (method, path, operationId,
 * parameters, response status codes) and the property names of every DTO schema must match.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "docs", "api", "url-shortener.openapi.yaml");
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head",
            "options");
    private static final List<String> DTO_SCHEMAS = List.of("CreateLinkRequest", "LinkResponse",
            "AnalyticsResponse", "DailyClicks", "ReferrerClicks", "UserAgentClicks");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void committedContractDeclaresTheGeneratedOperations() throws Exception {
        SortedMap<String, String> generated = operations(generated());

        assertThat(generated).containsOnlyKeys("POST /api/v1/links", "GET /api/v1/links/{code}",
                "DELETE /api/v1/links/{code}", "GET /api/v1/links/{code}/analytics", "GET /{code}");
        assertThat(operations(committed())).isEqualTo(generated);
    }

    @Test
    void committedSchemasDeclareTheDtoProperties() throws Exception {
        Map<String, Object> committed = committed();
        Map<String, Object> generated = generated();
        for (String schema : DTO_SCHEMAS) {
            assertThat(propertyNames(committed, schema)).as(schema).isEqualTo(propertyNames(generated, schema));
        }
    }

    @Test
    void committedContractIsOpenApi31() throws Exception {
        assertThat(committed().get("openapi").toString()).startsWith("3.1");
    }

    private Map<String, Object> generated() throws Exception {
        String json = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString();
        return asMap(objectMapper.readValue(json, Map.class));
    }

    private static Map<String, Object> committed() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONTRACT)) {
            return asMap(new Yaml().load(reader));
        }
    }

    private static SortedMap<String, String> operations(Map<String, Object> document) {
        SortedMap<String, String> operations = new TreeMap<>();
        asMap(document.get("paths")).forEach((path, pathItem) -> asMap(pathItem).forEach((method, value) -> {
            if (!HTTP_METHODS.contains(method)) {
                return;
            }
            Map<String, Object> operation = asMap(value);
            SortedSet<String> parameters = new TreeSet<>();
            for (Object parameter : asList(operation.get("parameters"))) {
                Map<String, Object> p = resolve(document, asMap(parameter));
                parameters.add(p.get("in") + ":" + p.get("name"));
            }
            SortedSet<String> statuses = new TreeSet<>();
            asMap(operation.get("responses")).keySet().forEach(code -> statuses.add(String.valueOf(code)));
            operations.put(method.toUpperCase(Locale.ROOT) + " " + path,
                    "operationId=" + operation.get("operationId") + " parameters=" + parameters
                            + " responses=" + statuses);
        }));
        return operations;
    }

    /** Resolves a local {@code $ref} such as {@code #/components/parameters/Code}. */
    private static Map<String, Object> resolve(Map<String, Object> document, Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref == null) {
            return node;
        }
        Map<String, Object> target = document;
        for (String segment : ref.toString().substring(2).split("/")) {
            target = asMap(target.get(segment));
        }
        return target;
    }

    private static SortedSet<String> propertyNames(Map<String, Object> document, String schema) {
        Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
        assertThat(schemas).as("schemas").containsKey(schema);
        return new TreeSet<>(asMap(asMap(schemas.get(schema)).get("properties")).keySet());
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
