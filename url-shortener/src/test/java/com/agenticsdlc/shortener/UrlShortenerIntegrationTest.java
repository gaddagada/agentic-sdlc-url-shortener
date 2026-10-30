package com.agenticsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.api.ProblemTypes;
import com.agenticsdlc.shortener.support.MutableClock;

/**
 * End-to-end through the HTTP layer with the real Spring context, Flyway-migrated in-memory H2, the async click
 * recorder and the rate-limit filter (capacity 2 in the {@code test} profile). Every test uses its own client
 * address, so rate-limit buckets never leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(UrlShortenerIntegrationTest.TestClockConfig.class)
class UrlShortenerIntegrationTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final AtomicInteger CLIENTS = new AtomicInteger(1);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MeterRegistry meters;

    private String clientIp;

    @BeforeEach
    void newClient() {
        clientIp = "203.0.113." + CLIENTS.getAndIncrement();
    }

    @Test
    void createRedirectRecordClickAnalyticsAndDeactivate() throws Exception {
        String code = create("{\"url\": \"https://example.com/docs?page=1\"}");

        MvcTestResult redirect = mvc.get().uri("/" + code)
                .header(HttpHeaders.REFERER, "https://news.example.org/post/1")
                .header(HttpHeaders.USER_AGENT, CHROME)
                .with(fromClient(clientIp)).exchange();
        assertThat(redirect).hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, "https://example.com/docs?page=1")
                .hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mvc.get().uri("/api/v1/links/" + code)).bodyJson()
                        .extractingPath("$.clickCount").isEqualTo(1));

        MvcTestResult analytics = mvc.get().uri("/api/v1/links/" + code + "/analytics?days=7").exchange();
        assertThat(analytics).hasStatusOk();
        assertThat(analytics).bodyJson().extractingPath("$.windowDays").isEqualTo(7);
        assertThat(analytics).bodyJson().extractingPath("$.totalClicks").isEqualTo(1);
        assertThat(analytics).bodyJson().extractingPath("$.uniqueVisitors").isEqualTo(1);
        assertThat(analytics).bodyJson().extractingPath("$.clicksByDay").asArray().hasSize(7);
        assertThat(analytics).bodyJson().extractingPath("$.clicksByDay[6].date").isEqualTo("2026-01-15");
        assertThat(analytics).bodyJson().extractingPath("$.clicksByDay[6].clicks").isEqualTo(1);
        assertThat(analytics).bodyJson().extractingPath("$.topReferrers[0].referrer").isEqualTo("news.example.org");
        assertThat(analytics).bodyJson().extractingPath("$.byUserAgentFamily[0].family").isEqualTo("CHROME");

        assertThat(mvc.delete().uri("/api/v1/links/" + code)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult gone = mvc.get().uri("/" + code).exchange();
        assertThat(gone).hasStatus(HttpStatus.GONE);
        assertThat(gone).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.LINK_GONE.toString());
        assertThat(gone).bodyJson().extractingPath("$.reason").isEqualTo("DISABLED");
        assertThat(mvc.get().uri("/api/v1/links/" + code)).bodyJson()
                .extractingPath("$.status").isEqualTo("DISABLED");
        assertThat(mvc.get().uri("/api/v1/links/" + code + "/analytics")).hasStatusOk();

        assertThat(meters.get("shortener.redirects").tag("outcome", "redirected").counter().count()).isPositive();
        assertThat(meters.get("shortener.redirects").tag("outcome", "gone").counter().count()).isPositive();
        assertThat(meters.get("shortener.links.created").tag("kind", "generated").counter().count()).isPositive();
        assertThat(meters.get("shortener.redirect.latency").timer().count()).isPositive();
    }

    @Test
    void expiredLinkAnswers410() throws Exception {
        String code = create("{\"url\": \"https://example.com/promo\", \"ttlSeconds\": 60}");
        assertThat(mvc.get().uri("/" + code)).hasStatus(HttpStatus.FOUND);

        clock.advance(Duration.ofSeconds(61));

        MvcTestResult expired = mvc.get().uri("/" + code).exchange();
        assertThat(expired).hasStatus(HttpStatus.GONE);
        assertThat(expired).bodyJson().extractingPath("$.reason").isEqualTo("EXPIRED");
        assertThat(mvc.get().uri("/api/v1/links/" + code)).bodyJson()
                .extractingPath("$.status").isEqualTo("EXPIRED");
    }

    @Test
    void unknownCodeAnswers404() {
        MvcTestResult result = mvc.get().uri("/doesNotExist").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.LINK_NOT_FOUND.toString());
    }

    @Test
    void idempotentRetryReplaysTheSameLink() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"url\": \"https://example.com/idempotent\"}";

        MvcTestResult first = postLink(body, key, clientIp);
        MvcTestResult retry = postLink(body, key, clientIp);

        assertThat(first).hasStatus(HttpStatus.CREATED).doesNotContainHeader("Idempotent-Replayed");
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true")
                .hasHeader(HttpHeaders.LOCATION, first.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(codeOf(retry)).isEqualTo(codeOf(first));

        MvcTestResult otherPayload = postLink("{\"url\": \"https://example.com/other\"}", key, "198.51.100.200");
        assertThat(otherPayload).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(otherPayload).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.IDEMPOTENCY_KEY_REUSED.toString());
    }

    @Test
    void rateLimitAnswers429OnceCapacityIsExhausted() {
        String body = "{\"url\": \"https://example.com/limited\"}";

        assertThat(postLink(body, null, clientIp)).hasStatus(HttpStatus.CREATED)
                .hasHeader("X-RateLimit-Remaining", "1");
        assertThat(postLink(body, null, clientIp)).hasStatus(HttpStatus.CREATED);
        MvcTestResult limited = postLink(body, null, clientIp);

        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "60")
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(limited).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RATE_LIMITED.toString());
        assertThat(limited).bodyJson().extractingPath("$.status").isEqualTo(429);
        assertThat(limited).bodyJson().extractingPath("$.instance").isEqualTo(LinkController.BASE_PATH);
    }

    @Test
    void spoofedForwardedForDoesNotBypassTheRateLimit() {
        // trust-forwarded-headers is false in the test profile: the socket address is the client identity.
        String body = "{\"url\": \"https://example.com/spoof\"}";

        for (int i = 1; i <= 2; i++) {
            assertThat(mvc.post().uri(LinkController.BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header("X-Forwarded-For", "10.9.9." + i).with(fromClient(clientIp)))
                    .hasStatus(HttpStatus.CREATED);
        }
        assertThat(mvc.post().uri(LinkController.BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Forwarded-For", "10.9.9.3").with(fromClient(clientIp)))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void customAliasRedirectsAndConflicts() {
        String alias = "launch-" + CLIENTS.get();
        String body = "{\"url\": \"https://example.com/launch\", \"customAlias\": \"" + alias + "\"}";

        assertThat(postLink(body, null, clientIp)).hasStatus(HttpStatus.CREATED)
                .hasHeader(HttpHeaders.LOCATION, "/api/v1/links/" + alias);
        assertThat(mvc.get().uri("/" + alias)).hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, "https://example.com/launch");

        MvcTestResult duplicate = postLink(body, null, clientIp);
        assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
        assertThat(duplicate).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.ALIAS_CONFLICT.toString());
    }

    @Test
    void reservedAliasIsRejected() {
        MvcTestResult result = postLink("{\"url\": \"https://example.com\", \"customAlias\": \"Admin\"}", null,
                clientIp);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.ALIAS_REJECTED.toString());
    }

    @Test
    void privateTargetsAreRejected() {
        MvcTestResult result = postLink("{\"url\": \"http://169.254.169.254/latest/meta-data/\"}", null, clientIp);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.URL_REJECTED.toString());
    }

    @Test
    void analyticsWindowIsValidated() throws Exception {
        String code = create("{\"url\": \"https://example.com/window\"}");

        for (String days : new String[] {"0", "91"}) {
            MvcTestResult result = mvc.get().uri("/api/v1/links/" + code + "/analytics?days=" + days).exchange();
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("days");
        }
        assertThat(mvc.get().uri("/api/v1/links/unknown1/analytics")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void healthAndMetricsAreExposed() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/metrics/shortener.links.created")).hasStatusOk();
    }

    private String create(String body) throws Exception {
        MvcTestResult result = postLink(body, null, clientIp);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return codeOf(result);
    }

    private MvcTestResult postLink(String body, String idempotencyKey, String ip) {
        var request = mvc.post().uri(LinkController.BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(fromClient(ip));
        if (idempotencyKey != null) {
            request.header(LinkController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return request.exchange();
    }

    private static String codeOf(MvcTestResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private static RequestPostProcessor fromClient(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
