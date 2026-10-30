package com.agenticsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The optional management API key through a real servlet container (Tomcat), where raw request paths are decoded and
 * normalized by the container exactly as in production. MockMvc cannot reproduce percent-encoded or path-parameter
 * spellings, so requests are sent verbatim with {@link HttpClient}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "shortener.security.api-key=" + ApiKeyServletContainerTest.API_KEY,
    "shortener.rate-limit.capacity=50",
    "shortener.rate-limit.refill-per-minute=50"})
@ActiveProfiles("test")
class ApiKeyServletContainerTest {

    static final String API_KEY = "it-api-key-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String BODY = "{\"url\": \"https://example.com/protected\"}";
    private static final String BULK_BODY = "{\"items\": [{\"url\": \"https://example.com/protected/bulk\"}]}";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void theManagementApiWorksWithTheKey() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/links", BODY, API_KEY);
        assertThat(created.statusCode()).isEqualTo(201);
        String code = JsonPath.read(created.body(), "$.code");

        assertThat(send("GET", "/api/v1/links/" + code, null, API_KEY).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/links/" + code + "/analytics", null, API_KEY).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/links/bulk", BULK_BODY, API_KEY).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", "/api/v1/links/" + code, null, API_KEY).statusCode()).isEqualTo(204);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', textBlock = """
        POST   | /api/v1/links
        POST   | /api/v1/links/bulk
        GET    | /api/v1/links/abcd1234
        HEAD   | /api/v1/links/abcd1234
        GET    | /api/v1/links/abcd1234/analytics
        DELETE | /api/v1/links/abcd1234
        POST   | /%61pi/v1/links
        POST   | /api/v1/%6Cinks/bulk
        POST   | /api/v1/links;x
        POST   | /api;v=1/v1/links
        GET    | /api/v1/%6cinks/abcd1234
        GET    | /api/v1/links/abcd1234/analytics;jsessionid=1
        DELETE | /%61pi/v1/links/abcd1234
        GET    | /api/v1/links/a%20b
        GET    | /api/v1/links/100%25
        """)
    void everySpellingOfTheManagementApiNeedsTheKey(String method, String rawPath) throws Exception {
        HttpResponse<String> withoutKey = send(method, rawPath, method.equals("POST") ? BODY : null, null);
        HttpResponse<String> wrongKey = send(method, rawPath, method.equals("POST") ? BODY : null, API_KEY + "x");

        for (HttpResponse<String> response : List.of(withoutKey, wrongKey)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("ApiKey realm=\"url-shortener\"");
            // Rejected before the rate limiter: an unauthenticated client cannot drain anybody's tokens.
            assertThat(response.headers().firstValue("X-RateLimit-Remaining")).isEmpty();
            assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            if (!method.equals("HEAD")) {
                assertThat(response.headers().firstValue("Content-Type"))
                        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
                assertThat(JsonPath.<String>read(response.body(), "$.type"))
                        .isEqualTo("https://agenticsdlc.dev/problems/unauthorized");
                assertThat(response.body()).doesNotContain(API_KEY);
            }
        }
    }

    @Test
    void redirectsActuatorAndApiDocsStayPublic() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/links", BODY, API_KEY);
        String code = JsonPath.read(created.body(), "$.code");

        HttpResponse<String> redirect = send("GET", "/" + code, null, null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).hasValue("https://example.com/protected");
        assertThat(send("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/v3/api-docs", null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/swagger-ui.html", null, null).statusCode()).isIn(200, 302);
        assertThat(send("OPTIONS", "/api/v1/links", null, null).statusCode()).isNotEqualTo(401);
    }

    private HttpResponse<String> send(String method, String rawPath, String body, String apiKey) throws Exception {
        URI uri = URI.create("http://localhost:" + port + rawPath);
        assertThat(uri.getRawPath()).isEqualTo(rawPath); // sent verbatim: not re-encoded or normalized
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (apiKey != null) {
            request.header("X-Api-Key", apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
