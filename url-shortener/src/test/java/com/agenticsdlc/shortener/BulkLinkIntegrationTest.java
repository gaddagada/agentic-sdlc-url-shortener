package com.agenticsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.jayway.jsonpath.JsonPath;

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

import com.agenticsdlc.shortener.api.ApiHeaders;
import com.agenticsdlc.shortener.api.BulkLinkController;
import com.agenticsdlc.shortener.api.LinkController;
import com.agenticsdlc.shortener.api.ProblemTypes;
import com.agenticsdlc.shortener.service.HostResolver;
import com.agenticsdlc.shortener.support.FakeHostResolver;

/**
 * Bulk creation end to end: real context, Flyway-migrated H2, rate-limit filter and limiter (capacity 10, refill
 * one token per minute, so buckets do not refill noticeably during a test), and the resolve-time target check on a
 * fake DNS. Every test uses its own client address, so buckets never leak between tests.
 */
@SpringBootTest(properties = {
    "shortener.rate-limit.capacity=10",
    "shortener.rate-limit.refill-per-minute=1",
    "shortener.resolve-targets=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BulkLinkIntegrationTest.FakeDnsConfig.class)
class BulkLinkIntegrationTest {

    private static final AtomicInteger CLIENTS = new AtomicInteger(1);

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeDnsConfig {

        @Bean
        @Primary
        HostResolver fakeHostResolver() {
            return new FakeHostResolver().answer("intranet.example.com", "10.0.0.5");
        }
    }

    @Autowired
    private MockMvcTester mvc;

    private String clientIp;
    private String suffix;

    @BeforeEach
    void newClient() {
        clientIp = "198.51.100." + CLIENTS.getAndIncrement();
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsTheValidItemsAndReportsEveryFailureLikeTheSingleEndpoint() throws Exception {
        String taken = "taken-" + suffix;
        assertThat(single("{\"url\": \"https://example.com/taken\", \"customAlias\": \"" + taken + "\"}",
                otherClient())).hasStatus(HttpStatus.CREATED);
        String dup = "dup-" + suffix;
        List<String> items = List.of(
                "{\"url\": \"https://example.com/bulk/ok\"}",
                "{\"url\": \"http://169.254.169.254/latest/meta-data/\"}",
                "{\"url\": \"https://example.com/bulk/admin\", \"customAlias\": \"admin\"}",
                "{\"url\": \"https://example.com/bulk/dup\", \"customAlias\": \"" + dup + "\"}",
                "{\"url\": \"https://example.com/bulk/dup-again\", \"customAlias\": \"" + dup + "\"}",
                "{\"url\": \"https://example.com/bulk/taken\", \"customAlias\": \"" + taken + "\"}",
                "{\"url\": \"https://example.com/bulk/past\", \"expiresAt\": \"2020-01-01T00:00:00Z\"}",
                "{\"url\": \"https://intranet.example.com/wiki\"}");

        MvcTestResult result = bulk(items, null, clientIp);

        assertThat(result).hasStatusOk().hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "2");
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.total")).isEqualTo(8);
        assertThat(JsonPath.<Integer>read(body, "$.created")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(body, "$.failed")).isEqualTo(6);
        assertThat(JsonPath.<List<String>>read(body, "$.results[*].status")).containsExactly(
                "CREATED", "FAILED", "FAILED", "CREATED", "FAILED", "FAILED", "FAILED", "FAILED");
        assertThat(JsonPath.<List<String>>read(body, "$.results[*].error.type")).containsExactly(
                ProblemTypes.URL_REJECTED.toString(), ProblemTypes.ALIAS_REJECTED.toString(),
                ProblemTypes.ALIAS_CONFLICT.toString(), ProblemTypes.ALIAS_CONFLICT.toString(),
                ProblemTypes.INVALID_EXPIRY.toString(), ProblemTypes.URL_REJECTED.toString());
        assertThat(JsonPath.<String>read(body, "$.results[4].error.detail")).contains("item 3 of this batch");
        assertThat(JsonPath.<String>read(body, "$.results[7].error.detail")).contains("resolves to a private");

        // The created items are committed despite the failures around them.
        String code = JsonPath.read(body, "$.results[0].link.code");
        assertThat(mvc.get().uri("/" + code)).hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, "https://example.com/bulk/ok");
        assertThat(mvc.get().uri("/" + dup)).hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, "https://example.com/bulk/dup");

        // Every failed item carries exactly the type and status the single-link endpoint answers for it.
        for (int index : new int[] {1, 2, 5, 6, 7}) {
            MvcTestResult alone = single(items.get(index), otherClient());
            String aloneBody = alone.getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(body, "$.results[" + index + "].error.status")).as("item " + index)
                    .isEqualTo(alone.getResponse().getStatus());
            Map<String, Object> error = JsonPath.read(body, "$.results[" + index + "].error");
            assertThat(error).as("item " + index)
                    .containsEntry("type", JsonPath.read(aloneBody, "$.type"))
                    .containsEntry("title", JsonPath.read(aloneBody, "$.title"))
                    .containsKey("detail")
                    .hasSize(4);
        }
    }

    @Test
    void chargesOneTokenPerItemSharedWithTheSingleEndpoint() throws Exception {
        assertThat(bulk(urls(6, "a"), null, clientIp)).hasStatusOk()
                .hasHeader(ApiHeaders.RATE_LIMIT_LIMIT, "10")
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "4");

        List<String> aliased = IntStream.range(0, 5)
                .mapToObj(i -> "{\"url\": \"https://example.com/limited\", \"customAlias\": \"lim-" + i + "-"
                        + suffix + "\"}")
                .toList();
        MvcTestResult limited = bulk(aliased, null, clientIp);
        assertThat(limited).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "4");
        // One token is missing at one token per minute (minus the few milliseconds since the first request).
        assertThat(Integer.parseInt(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(55, 60);
        assertThat(limited).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RATE_LIMITED.toString());
        assertThat(mvc.get().uri("/api/v1/links/lim-0-" + suffix)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(single("{\"url\": \"https://example.com/single\"}", clientIp)).hasStatus(HttpStatus.CREATED)
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "3");
        assertThat(bulk(urls(3, "b"), null, clientIp)).hasStatusOk()
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "0");
        assertThat(single("{\"url\": \"https://example.com/single\"}", clientIp))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void rejectedBatchesAreNotCharged() {
        MvcTestResult tooLargeForTheBucket = bulk(urls(11, "c"), null, clientIp);
        assertThat(tooLargeForTheBucket).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooLargeForTheBucket).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.BATCH_TOO_LARGE.toString());
        assertThat(tooLargeForTheBucket).bodyJson().extractingPath("$.maxItems").isEqualTo(10);

        MvcTestResult tooMany = bulk(urls(101, "d"), null, clientIp);
        assertThat(tooMany).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooMany).bodyJson().extractingPath("$.errors[0].field").isEqualTo("items");

        MvcTestResult invalidItem = bulk(List.of("{\"url\": \"https://example.com/\"}", "{\"url\": \"\"}"), null,
                clientIp);
        assertThat(invalidItem).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidItem).bodyJson().extractingPath("$.errors[0].field").isEqualTo("items[1].url");

        assertThat(bulk(urls(10, "e"), null, clientIp)).hasStatusOk()
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "0");
    }

    @Test
    void anIdempotentRetryReplaysTheWholeResponseWithoutCreatingOrChargingAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        String alias = "idem-" + suffix;
        List<String> items = List.of(
                "{\"url\": \"https://example.com/idem\", \"customAlias\": \"" + alias + "\"}",
                "{\"url\": \"ftp://example.com/file\"}");

        MvcTestResult first = bulk(items, key, clientIp);
        MvcTestResult retry = bulk(items, key, clientIp);

        assertThat(first).hasStatusOk().doesNotContainHeader(ApiHeaders.IDEMPOTENT_REPLAYED)
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "8");
        assertThat(retry).hasStatusOk().hasHeader(ApiHeaders.IDEMPOTENT_REPLAYED, "true")
                .doesNotContainHeader(ApiHeaders.RATE_LIMIT_REMAINING);
        // Re-executing would have failed the alias with alias-conflict: the replay returns the stored outcome.
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(JsonPath.<String>read(first.getResponse().getContentAsString(), "$.results[0].status"))
                .isEqualTo("CREATED");
        assertThat(single("{\"url\": \"https://example.com/x\"}", clientIp))
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "7"); // the replay did not consume tokens

        MvcTestResult otherItems = bulk(List.of("{\"url\": \"https://example.com/other\"}"), key, clientIp);
        assertThat(otherItems).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(otherItems).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.IDEMPOTENCY_KEY_REUSED.toString());
        MvcTestResult singleWithBulkKey = mvc.post().uri(LinkController.BASE_PATH)
                .header(ApiHeaders.IDEMPOTENCY_KEY, key).contentType(MediaType.APPLICATION_JSON)
                .content(items.getFirst()).with(request -> {
                    request.setRemoteAddr(otherClient());
                    return request;
                }).exchange();
        assertThat(singleWithBulkKey).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    private List<String> urls(int count, String prefix) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"url\": \"https://example.com/" + prefix + "/" + suffix + "/" + i + "\"}")
                .toList();
    }

    private MvcTestResult bulk(List<String> items, String idempotencyKey, String ip) {
        var request = mvc.post().uri(BulkLinkController.PATH).contentType(MediaType.APPLICATION_JSON)
                .content(items.stream().collect(Collectors.joining(",", "{\"items\": [", "]}")))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        if (idempotencyKey != null) {
            request.header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return request.exchange();
    }

    private MvcTestResult single(String body, String ip) {
        return mvc.post().uri(LinkController.BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }).exchange();
    }

    private static String otherClient() {
        return "198.51.100." + CLIENTS.getAndIncrement();
    }
}
