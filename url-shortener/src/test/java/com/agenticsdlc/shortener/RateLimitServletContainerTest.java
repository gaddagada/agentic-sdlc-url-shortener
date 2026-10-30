package com.agenticsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rate limiting through a real servlet container (Tomcat). MockMvc cannot reproduce path-parameter or
 * percent-encoded spellings of the request URI because it does not decode and normalize paths the way the container
 * does, so raw request paths are sent with {@link HttpClient} instead. Capacity is 1 for this context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"shortener.rate-limit.capacity=1", "shortener.rate-limit.refill-per-minute=1"})
@ActiveProfiles("test")
class RateLimitServletContainerTest {

    private static final String BODY = "{\"url\": \"https://example.com/container\"}";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void encodedAndPathParameterSpellingsCannotBypassTheLinkCreationLimit() throws Exception {
        HttpResponse<String> created = post("/api/v1/links");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("X-RateLimit-Remaining")).hasValue("0");
        assertSecurityHeaders(created);

        assertThat(post("/api/v1/links").statusCode()).isEqualTo(429);

        HttpResponse<String> pathParameter = post("/api/v1/links;x");
        assertThat(pathParameter.statusCode()).isEqualTo(429);
        assertThat(pathParameter.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(pathParameter.headers().firstValue("Retry-After")).isPresent();
        assertThat(JsonPath.<String>read(pathParameter.body(), "$.instance")).isEqualTo("/api/v1/links");
        assertSecurityHeaders(pathParameter);

        // The container decodes %6C to 'l'. 404 (not routed) would also be safe; 201 would be a bypass.
        assertThat(post("/api/v1/%6Cinks").statusCode()).isIn(429, 404);
        assertThat(post("/api/v1/%6cinks;jsessionid=abc").statusCode()).isIn(429, 404);
        assertThat(post("/api/v1/links/bulk").statusCode()).isEqualTo(429);

        // Redirects are not rate limited and keep their headers in the real container.
        String code = JsonPath.read(created.body(), "$.code");
        HttpResponse<String> redirect = http.send(HttpRequest.newBuilder(uri("/" + code)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).hasValue("https://example.com/container");
        assertThat(redirect.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertSecurityHeaders(redirect);
    }

    private HttpResponse<String> post(String rawPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(rawPath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String rawPath) {
        URI uri = URI.create("http://localhost:" + port + rawPath);
        assertThat(uri.getRawPath()).isEqualTo(rawPath); // sent verbatim: not re-encoded or normalized
        return uri;
    }

    private static void assertSecurityHeaders(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }
}
