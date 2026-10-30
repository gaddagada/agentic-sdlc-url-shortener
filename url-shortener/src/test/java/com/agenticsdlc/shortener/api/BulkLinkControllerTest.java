package com.agenticsdlc.shortener.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.service.BulkCreation;
import com.agenticsdlc.shortener.service.BulkCreation.Item;
import com.agenticsdlc.shortener.service.BulkCreationResult;
import com.agenticsdlc.shortener.service.BulkLinkService;
import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.service.CreateLinkCommand;
import com.agenticsdlc.shortener.service.ItemError;
import com.agenticsdlc.shortener.service.LinkView;
import com.agenticsdlc.shortener.service.exception.BatchTooLargeException;
import com.agenticsdlc.shortener.service.exception.IdempotencyConflictException;
import com.agenticsdlc.shortener.service.exception.IdempotencyInProgressException;
import com.agenticsdlc.shortener.service.exception.RateLimitExceededException;

@WebMvcTest(BulkLinkController.class)
class BulkLinkControllerTest {

    private static final String CLIENT = "192.0.2.44";
    private static final LinkView VIEW = new LinkView("abc1234", "https://sho.rt/abc1234", "https://example.com/a",
            LinkStatus.ACTIVE, Instant.parse("2026-01-15T10:00:00Z"), null, 0);
    private static final ItemError CONFLICT = new ItemError(ProblemTypes.ALIAS_CONFLICT, "Alias already in use", 409,
            "The alias 'promo' is already in use.");
    private static final BulkCreation MIXED = new BulkCreation(List.of(Item.created(0, VIEW),
            Item.failed(1, CONFLICT)));
    private static final String TWO_ITEMS = """
            {"items": [{"url": "https://example.com/a"}, {"url": "https://example.com/b", "customAlias": "promo"}]}
            """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private BulkLinkService bulkLinkService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @BeforeEach
    void resolveClient() {
        when(clientIpResolver.rateLimitKey(anyString(), any())).thenReturn(CLIENT);
    }

    @Test
    void answers200WithOneResultPerItemAndTheRateLimitHeaders() {
        when(bulkLinkService.createAll(any(), isNull(), eq(CLIENT)))
                .thenReturn(new BulkCreationResult(MIXED, false, new BulkCreationResult.RateLimitStatus(20, 18)));

        MvcTestResult result = post(TWO_ITEMS);

        assertThat(result).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .hasHeader(ApiHeaders.RATE_LIMIT_LIMIT, "20")
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "18")
                .doesNotContainHeader(ApiHeaders.IDEMPOTENT_REPLAYED);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "total": 2,
                  "created": 1,
                  "failed": 1,
                  "results": [
                    {
                      "index": 0,
                      "status": "CREATED",
                      "link": {
                        "code": "abc1234",
                        "shortUrl": "https://sho.rt/abc1234",
                        "originalUrl": "https://example.com/a",
                        "status": "ACTIVE",
                        "createdAt": "2026-01-15T10:00:00Z",
                        "clickCount": 0
                      },
                      "error": null
                    },
                    {
                      "index": 1,
                      "status": "FAILED",
                      "link": null,
                      "error": {
                        "type": "https://agenticsdlc.dev/problems/alias-conflict",
                        "title": "Alias already in use",
                        "status": 409,
                        "detail": "The alias 'promo' is already in use."
                      }
                    }
                  ]
                }
                """);
    }

    @Test
    void mapsTheItemsToCommandsInOrder() {
        when(bulkLinkService.createAll(any(), isNull(), eq(CLIENT))).thenReturn(new BulkCreationResult(MIXED, false,
                null));

        post(TWO_ITEMS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CreateLinkCommand>> commands = ArgumentCaptor.forClass(List.class);
        verify(bulkLinkService).createAll(commands.capture(), isNull(), eq(CLIENT));
        assertThat(commands.getValue()).containsExactly(
                new CreateLinkCommand("https://example.com/a", null, null, null),
                new CreateLinkCommand("https://example.com/b", "promo", null, null));
    }

    @Test
    void passesTheIdempotencyKeyAndMarksReplaysWithoutRateLimitHeaders() {
        when(bulkLinkService.createAll(any(), eq("key-1"), eq(CLIENT)))
                .thenReturn(new BulkCreationResult(MIXED, true, null));

        MvcTestResult result = mvc.post().uri(BulkLinkController.PATH).header(ApiHeaders.IDEMPOTENCY_KEY, "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(TWO_ITEMS).exchange();

        assertThat(result).hasStatusOk()
                .hasHeader(ApiHeaders.IDEMPOTENT_REPLAYED, "true")
                .doesNotContainHeader(ApiHeaders.RATE_LIMIT_REMAINING);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        {}                                                                         | items
        {"items": null}                                                            | items
        {"items": []}                                                              | items
        {"items": [null]}                                                          | items[0]
        {"items": [{"url": "https://example.com"}, {"url": ""}]}                   | items[1].url
        {"items": [{"url": "https://example.com", "customAlias": "a b"}]}          | items[0].customAlias
        {"items": [{"url": "https://example.com", "ttlSeconds": 0}]}               | items[0].ttlSeconds
        {"items": [{"url": "https://e.com", "ttlSeconds": 9, "expiresAt": "2030-01-01T00:00:00Z"}]} \
            | items[0].expiryUnambiguous
        """)
    void invalidRequestsAreValidationProblems(String body, String field) {
        MvcTestResult result = post(body);

        assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains(field);
        verifyNoInteractions(bulkLinkService);
    }

    @Test
    void moreThan100ItemsIsAValidationProblem() {
        String items = IntStream.range(0, BulkLinkService.MAX_ITEMS + 1)
                .mapToObj(i -> "{\"url\": \"https://example.com/" + i + "\"}")
                .collect(Collectors.joining(",", "{\"items\": [", "]}"));

        MvcTestResult result = post(items);

        assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("items");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").asString().contains("100");
        verifyNoInteractions(bulkLinkService);
    }

    @Test
    void exactly100ItemsAreAccepted() {
        String items = IntStream.range(0, BulkLinkService.MAX_ITEMS)
                .mapToObj(i -> "{\"url\": \"https://example.com/" + i + "\"}")
                .collect(Collectors.joining(",", "{\"items\": [", "]}"));
        when(bulkLinkService.createAll(any(), isNull(), eq(CLIENT)))
                .thenReturn(new BulkCreationResult(MIXED, false, null));

        assertThat(post(items)).hasStatusOk();
    }

    @Test
    void malformedJsonIsAProblem() {
        assertProblem(post("{\"items\": ["), HttpStatus.BAD_REQUEST, "malformed-request");
    }

    @Test
    void tooLongIdempotencyKeyIsAValidationProblem() {
        MvcTestResult result = mvc.post().uri(BulkLinkController.PATH)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "k".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).content(TWO_ITEMS).exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
        verifyNoInteractions(bulkLinkService);
    }

    @Test
    void notEnoughTokensIs429WithRetryAfterAndRateLimitHeaders() {
        when(bulkLinkService.createAll(any(), isNull(), eq(CLIENT)))
                .thenThrow(new RateLimitExceededException(2, 20, 1, 3));

        MvcTestResult result = post(TWO_ITEMS);

        assertProblem(result, HttpStatus.TOO_MANY_REQUESTS, "rate-limited");
        assertThat(result).hasHeader(HttpHeaders.RETRY_AFTER, "3")
                .hasHeader(ApiHeaders.RATE_LIMIT_LIMIT, "20")
                .hasHeader(ApiHeaders.RATE_LIMIT_REMAINING, "1");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(BulkLinkController.PATH);
    }

    @Test
    void aBatchLargerThanTheRateLimitCapacityIs422() {
        when(bulkLinkService.createAll(any(), isNull(), eq(CLIENT))).thenThrow(new BatchTooLargeException(2, 1));

        MvcTestResult result = post(TWO_ITEMS);

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "batch-too-large");
        assertThat(result).bodyJson().extractingPath("$.maxItems").isEqualTo(1);
    }

    @Test
    void idempotencyConflictsAreTheSameProblemsAsOnTheSingleEndpoint() {
        when(bulkLinkService.createAll(any(), eq("reused"), eq(CLIENT))).thenThrow(new IdempotencyConflictException());
        when(bulkLinkService.createAll(any(), eq("running"), eq(CLIENT)))
                .thenThrow(new IdempotencyInProgressException(97));

        MvcTestResult reused = mvc.post().uri(BulkLinkController.PATH).header(ApiHeaders.IDEMPOTENCY_KEY, "reused")
                .contentType(MediaType.APPLICATION_JSON).content(TWO_ITEMS).exchange();
        MvcTestResult running = mvc.post().uri(BulkLinkController.PATH).header(ApiHeaders.IDEMPOTENCY_KEY, "running")
                .contentType(MediaType.APPLICATION_JSON).content(TWO_ITEMS).exchange();

        assertProblem(reused, HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused");
        assertProblem(running, HttpStatus.CONFLICT, "idempotency-request-in-progress");
        assertThat(running).as("the time left until the reservation in flight may be taken over")
                .hasHeader(HttpHeaders.RETRY_AFTER, "97");
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(BulkLinkController.PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.BASE + slug);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    }
}
