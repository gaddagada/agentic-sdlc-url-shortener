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
 * double-slash variants of {@code /api/v1/runs} must never reach a controller without the key, the key opens all
 * of them, the console, actuator and OpenAPI document stay open, and every answer carries the security headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sdlc.security.api-key=test-key")
@ActiveProfiles("test")
class ApiKeyAuthFilterServletTest {

    private static final String KEY = "test-key";
    private static final List<String> API_PATH_VARIANTS = List.of("/api/v1/runs", "/%61pi/v1/runs",
            "/api;x=1/v1/runs", "/api/v1//runs", "/%61%70%69/v1/runs", "/api/v1/runs;jsessionid=x");

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
        assertThat(get("/api/v1/runs", "wrong-key").statusCode()).isEqualTo(401);
    }

    @Test
    void theKeyOpensEveryEncodingOfAnApiPath() throws Exception {
        for (String path : API_PATH_VARIANTS) {
            HttpResponse<String> response = get(path, KEY);
            if (path.contains("//")) {
                // passes the key check, but Spring MVC path patterns do not route empty segments: 404 Problem Detail
                assertThat(response.statusCode()).as("GET %s with key", path).isEqualTo(404);
            } else {
                assertThat(response.statusCode()).as("GET %s with key", path).isEqualTo(200);
                assertThat(response.body()).as("GET %s with key", path).startsWith("[");
            }
        }
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

    private HttpResponse<String> get(String rawPath, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .timeout(Duration.ofSeconds(10)).GET();
        if (key != null) {
            request.header(ApiKeyAuthFilter.HEADER, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
