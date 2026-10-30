package com.agenticsdlc.shortener.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

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
import com.agenticsdlc.shortener.service.CreateLinkCommand;
import com.agenticsdlc.shortener.service.CreatedLink;
import com.agenticsdlc.shortener.service.LinkService;
import com.agenticsdlc.shortener.service.LinkView;
import com.agenticsdlc.shortener.service.exception.AliasConflictException;
import com.agenticsdlc.shortener.service.exception.AliasRejectedException;
import com.agenticsdlc.shortener.service.exception.CodeGenerationException;
import com.agenticsdlc.shortener.service.exception.IdempotencyConflictException;
import com.agenticsdlc.shortener.service.exception.IdempotencyInProgressException;
import com.agenticsdlc.shortener.service.exception.InvalidExpiryException;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;
import com.agenticsdlc.shortener.service.exception.UrlRejectedException;

@WebMvcTest(LinkController.class)
class LinkControllerTest {

    private static final Instant CREATED = Instant.parse("2026-01-15T10:00:00Z");
    private static final LinkView VIEW = new LinkView("abc1234", "https://sho.rt/abc1234", "https://example.com/x",
            LinkStatus.ACTIVE, CREATED, null, 0);
    private static final String VALID_BODY = """
            {"url": "https://example.com/x"}
            """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LinkService linkService;

    @Test
    void createReturns201WithLocationAndBody() {
        when(linkService.create(any(), isNull())).thenReturn(new CreatedLink(VIEW, false));

        MvcTestResult result = post(VALID_BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader(HttpHeaders.LOCATION, "/api/v1/links/abc1234")
                .doesNotContainHeader(LinkController.IDEMPOTENT_REPLAYED_HEADER)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "code": "abc1234",
                  "shortUrl": "https://sho.rt/abc1234",
                  "originalUrl": "https://example.com/x",
                  "status": "ACTIVE",
                  "createdAt": "2026-01-15T10:00:00Z",
                  "clickCount": 0
                }
                """);
    }

    @Test
    void mapsTheRequestToACommand() {
        when(linkService.create(any(), isNull())).thenReturn(new CreatedLink(VIEW, false));

        post("""
                {"url": "https://example.com/x", "customAlias": "spring-sale", "ttlSeconds": 3600}
                """);

        ArgumentCaptor<CreateLinkCommand> command = ArgumentCaptor.forClass(CreateLinkCommand.class);
        verify(linkService).create(command.capture(), isNull());
        assertThat(command.getValue())
                .isEqualTo(new CreateLinkCommand("https://example.com/x", "spring-sale", 3600L, null));
    }

    @Test
    void passesIdempotencyKeyAndMarksReplays() {
        when(linkService.create(any(), eq("key-1"))).thenReturn(new CreatedLink(VIEW, true));

        MvcTestResult result = mvc.post().uri(LinkController.BASE_PATH)
                .header(LinkController.IDEMPOTENCY_KEY_HEADER, "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader(LinkController.IDEMPOTENT_REPLAYED_HEADER, "true");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        {}                                                                 | url
        {"url": ""}                                                        | url
        {"url": "https://example.com/x", "customAlias": "a b"}             | customAlias
        {"url": "https://example.com/x", "customAlias": "abc"}             | customAlias
        {"url": "https://example.com/x", "ttlSeconds": 0}                  | ttlSeconds
        {"url": "https://example.com/x", "ttlSeconds": 60, "expiresAt": "2030-01-01T00:00:00Z"} | expiryUnambiguous
        """)
    void invalidRequestIsValidationProblem(String body, String field) {
        MvcTestResult result = post(body);

        assertValidationProblem(result, field);
        verifyNoInteractions(linkService);
    }

    @Test
    void tooLongUrlIsValidationProblem() {
        String url = "https://example.com/" + "a".repeat(2048);

        assertValidationProblem(post("{\"url\": \"" + url + "\"}"), "url");
    }

    @Test
    void tooLongIdempotencyKeyIsValidationProblem() {
        MvcTestResult result = mvc.post().uri(LinkController.BASE_PATH)
                .header(LinkController.IDEMPOTENCY_KEY_HEADER, "k".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

        assertValidationProblem(result, LinkController.IDEMPOTENCY_KEY_HEADER);
        verifyNoInteractions(linkService);
    }

    @Test
    void malformedJsonIsProblem() {
        MvcTestResult result = post("{\"url\": ");

        assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
    }

    @Test
    void unsupportedMediaTypeIsProblem() {
        MvcTestResult result = mvc.post().uri(LinkController.BASE_PATH)
                .contentType(MediaType.TEXT_PLAIN).content("https://example.com").exchange();

        assertThat(result).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aliasConflictIs409() {
        when(linkService.create(any(), isNull())).thenThrow(new AliasConflictException("spring-sale"));

        MvcTestResult result = post(VALID_BODY);

        assertProblem(result, HttpStatus.CONFLICT, "alias-conflict");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/links");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("spring-sale");
    }

    @Test
    void rejectedUrlIs422() {
        when(linkService.create(any(), isNull()))
                .thenThrow(new UrlRejectedException("Target host '10.0.0.1' is a private address."));

        MvcTestResult result = post(VALID_BODY);

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "url-rejected");
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("Target host '10.0.0.1' is a private address.");
    }

    @Test
    void reservedAliasIs422() {
        when(linkService.create(any(), isNull())).thenThrow(new AliasRejectedException("admin", "it is reserved"));

        assertProblem(post(VALID_BODY), HttpStatus.UNPROCESSABLE_CONTENT, "alias-rejected");
    }

    @Test
    void reusedIdempotencyKeyIs422() {
        when(linkService.create(any(), isNull())).thenThrow(new IdempotencyConflictException());

        assertProblem(post(VALID_BODY), HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused");
    }

    @Test
    void idempotentRequestInProgressIs409WithRetryAfter() {
        when(linkService.create(any(), isNull())).thenThrow(new IdempotencyInProgressException());

        MvcTestResult result = post(VALID_BODY);

        assertProblem(result, HttpStatus.CONFLICT, "idempotency-request-in-progress");
        assertThat(result).hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void invalidExpiryIs400() {
        when(linkService.create(any(), isNull()))
                .thenThrow(new InvalidExpiryException("expiresAt must be in the future."));

        assertProblem(post(VALID_BODY), HttpStatus.BAD_REQUEST, "invalid-expiry");
    }

    @Test
    void exhaustedCodeGenerationIs503WithRetryAfter() {
        when(linkService.create(any(), isNull())).thenThrow(new CodeGenerationException(4));

        MvcTestResult result = post(VALID_BODY);

        assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "code-generation-failed");
        assertThat(result).hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void unexpectedErrorIs500WithoutInternals() {
        when(linkService.create(any(), isNull())).thenThrow(new IllegalStateException("jdbc password=hunter2"));

        MvcTestResult result = post(VALID_BODY);

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
        assertThat(result).body().asString().doesNotContain("hunter2");
    }

    @Test
    void getReturnsLink() {
        when(linkService.get("abc1234")).thenReturn(VIEW);

        MvcTestResult result = mvc.get().uri("/api/v1/links/abc1234").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.shortUrl").isEqualTo("https://sho.rt/abc1234");
    }

    @Test
    void getUnknownIs404() {
        when(linkService.get("missing")).thenThrow(new LinkNotFoundException("missing"));

        assertProblem(mvc.get().uri("/api/v1/links/missing").exchange(), HttpStatus.NOT_FOUND, "link-not-found");
    }

    @Test
    void deleteReturns204() {
        assertThat(mvc.delete().uri("/api/v1/links/abc1234")).hasStatus(HttpStatus.NO_CONTENT);
        verify(linkService).deactivate("abc1234");
    }

    @Test
    void deleteUnknownIs404() {
        doThrow(new LinkNotFoundException("missing")).when(linkService).deactivate("missing");

        assertProblem(mvc.delete().uri("/api/v1/links/missing").exchange(), HttpStatus.NOT_FOUND, "link-not-found");
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(LinkController.BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();
    }

    private static void assertValidationProblem(MvcTestResult result, String field) {
        assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains(field);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.BASE + slug);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
        assertThat(result).bodyJson().extractingPath("$.title").asString().isNotBlank();
    }
}
