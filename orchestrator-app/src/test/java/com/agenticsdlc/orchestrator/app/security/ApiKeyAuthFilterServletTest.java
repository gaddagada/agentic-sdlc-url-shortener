package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link ApiKeyAuthFilter} and {@link SecurityHeadersFilter} in the real servlet container (embedded Tomcat on a
 * random port), driven with raw request paths through {@link HttpClient}: encoded, parameterized and
 * double-slash variants of {@code /api/v1/runs} must never reach a controller without the key, the key opens the
 * canonical and percent-encoded paths, the console, actuator and OpenAPI document stay open, and every answer
 * carries the security headers.
 *
 * <p>Only {@code sdlc.security.api-key} is set (no {@code sdlc.security.mode}): the backwards-compatible way of
 * selecting {@code api-key} mode. With the key a caller holds every role. Since the Spring Security filter chain
 * runs behind the key filter, non-normalized paths ({@code ;} path parameters, {@code //}) are rejected with 400 by
 * its {@code StrictHttpFirewall} even with the key (they used to be routed); they never reach a controller.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sdlc.security.api-key=test-key")
@ActiveProfiles("test")
class ApiKeyAuthFilterServletTest {

    private static final String KEY = "test-key";
    private static final List<String> API_PATH_VARIANTS = List.of("/api/v1/runs", "/%61pi/v1/runs",
            "/api;x=1/v1/runs", "/api/v1//runs", "/%61%70%69/v1/runs", "/api/v1/runs;jsessionid=x");
    private static final List<String> NON_NORMALIZED_VARIANTS = List.of("/api;x=1/v1/runs", "/api/v1//runs",
            "/api/v1/runs;jsessionid=x");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Test
    void everyEncodingOfAnApiPathNeedsTheKey() throws Exception {
        for (String path : API_PATH_VARIANTS) {
            HttpResponse<String> response = get(path, null);

            assertThat(response.statusCode()).as("GET %s without key", path).isNotEqualTo(200)
                    .isIn(401, 400, 404);
            assertThat(response.body()).as("GET %s without key", path).doesNotContain("\"scenario\"");
        }
        HttpResponse<String> plain = get("/api/v1/runs", null);
        assertThat(plain.statusCode()).isEqualTo(401);
        assertThat(plain.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(plain.headers().firstValue("WWW-Authenticate")).hasValue(ApiKeyAuthFilter.CHALLENGE);
        assertThat(get("/api/v1/runs", "wrong-key").statusCode()).isEqualTo(401);
    }

    @Test
    void theKeyOpensTheCanonicalAndPercentEncodedApiPaths() throws Exception {
        for (String path : API_PATH_VARIANTS) {
            HttpResponse<String> response = get(path, KEY);
            if (NON_NORMALIZED_VARIANTS.contains(path)) {
                // passes the key check, then Spring Security's firewall refuses the non-normalized path
                assertThat(response.statusCode()).as("GET %s with key", path).isEqualTo(400);
                assertThat(response.body()).as("GET %s with key", path).doesNotContain("\"scenario\"");
            } else {
                assertThat(response.statusCode()).as("GET %s with key", path).isEqualTo(200);
                assertThat(response.body()).as("GET %s with key", path).startsWith("{\"items\":[");
            }
        }
    }

    @Test
    void theKeyGrantsEveryRole() throws Exception {
        assertThat(post("/api/v1/workspace/reindex", KEY, "admin.user").statusCode()).as("ADMIN").isEqualTo(200);
        assertThat(post("/api/v1/runs/no-such-run/stages/design/approve", KEY, "approver.user").statusCode())
                .as("APPROVER: authorized, then 404 from the controller").isEqualTo(404);
        assertThat(post("/api/v1/runs/no-such-run/stop", KEY, "operator.user").statusCode())
                .as("OPERATOR: authorized, then 404 from the controller").isEqualTo(404);
        assertThat(post("/api/v1/workspace/reindex", KEY, null).statusCode())
                .as("the operator header is still required").isEqualTo(400);
        assertThat(post("/api/v1/workspace/reindex", null, "admin.user").statusCode()).isEqualTo(401);
    }

    @Test
    void consoleActuatorAndApiDocsStayOpen() throws Exception {
        for (String path : List.of("/", "/index.html", "/app.js", "/js/api.js", "/actuator/health", "/v3/api-docs")) {
            assertThat(get(path, null).statusCode()).as("GET %s", path).isEqualTo(200);
        }
    }

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        for (String path : List.of("/", "/app.js", "/api/v1/runs", "/actuator/health", "/no-such-page")) {
            for (String key : new String[] {null, KEY}) {
                HttpResponse<String> response = get(path, key);

                assertThat(response.headers().firstValue("X-Content-Type-Options")).as(path).hasValue("nosniff");
                assertThat(response.headers().firstValue("X-Frame-Options")).as(path).hasValue("DENY");
                assertThat(response.headers().firstValue("Referrer-Policy")).as(path).hasValue("no-referrer");
                assertThat(response.headers().firstValue("Content-Security-Policy")).as(path)
                        .hasValue(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
            }
        }
    }

    @Test
    void healthShowsComponentStatusesWithoutDetails() throws Exception {
        HttpResponse<String> health = get("/actuator/health", null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"components\"").contains("\"workspaceIndex\"")
                .doesNotContain("\"details\"").doesNotContain("\"root\"");
    }

    private HttpResponse<String> post(String rawPath, String key, String operator)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (key != null) {
            request.header(ApiKeyAuthFilter.HEADER, key);
        }
        if (operator != null) {
            request.header("X-Operator-Id", operator);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String rawPath, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .timeout(Duration.ofSeconds(10)).GET();
        if (key != null) {
            request.header(ApiKeyAuthFilter.HEADER, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
