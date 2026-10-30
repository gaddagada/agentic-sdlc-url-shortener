package com.agenticsdlc.shortener.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.service.ClickRecorded;
import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.service.LinkService;
import com.agenticsdlc.shortener.service.RedirectTarget;
import com.agenticsdlc.shortener.service.exception.LinkGoneException;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;
import com.agenticsdlc.shortener.support.MutableClock;

@WebMvcTest(RedirectController.class)
@Import(RedirectControllerTest.Config.class)
@RecordApplicationEvents
class RedirectControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final URI TARGET = URI.create("https://example.com/landing?utm=1");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private LinkService linkService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private RedirectMetrics metrics;

    @Test
    void redirectsWith302NoStoreAndPublishesClick() {
        when(linkService.resolveRedirect("abc1234")).thenReturn(new RedirectTarget(42L, TARGET));
        when(clientIpResolver.resolve("198.51.100.7", null)).thenReturn("198.51.100.7");

        MvcTestResult result = mvc.get().uri("/abc1234")
                .header(HttpHeaders.REFERER, "https://news.example.org/post")
                .header(HttpHeaders.USER_AGENT, "curl/8.4.0")
                .with(request -> {
                    request.setRemoteAddr("198.51.100.7");
                    return request;
                })
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, TARGET.toString())
                .hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(events.stream(ClickRecorded.class)).containsExactly(new ClickRecorded(42L, NOW,
                "https://news.example.org/post", "curl/8.4.0", "198.51.100.7"));
        verify(metrics).record(eq(RedirectMetrics.Outcome.REDIRECTED), anyLong());
    }

    @Test
    void headRequestsAreNotCountedAsClicks() {
        when(linkService.resolveRedirect("abc1234")).thenReturn(new RedirectTarget(42L, TARGET));

        assertThat(mvc.head().uri("/abc1234")).hasStatus(HttpStatus.FOUND);
        assertThat(events.stream(ClickRecorded.class)).isEmpty();
    }

    @Test
    void unknownCodeIs404AndNotCacheable() {
        when(linkService.resolveRedirect("missing1")).thenThrow(new LinkNotFoundException("missing1"));

        MvcTestResult result = mvc.get().uri("/missing1").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.LINK_NOT_FOUND.toString());
        assertThat(events.stream(ClickRecorded.class)).isEmpty();
        verify(metrics).record(eq(RedirectMetrics.Outcome.NOT_FOUND), anyLong());
    }

    @Test
    void expiredLinkIs410() {
        when(linkService.resolveRedirect("abc1234")).thenThrow(new LinkGoneException("abc1234", LinkStatus.EXPIRED));

        MvcTestResult result = mvc.get().uri("/abc1234").exchange();

        assertThat(result).hasStatus(HttpStatus.GONE).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.LINK_GONE.toString());
        assertThat(result).bodyJson().extractingPath("$.reason").isEqualTo("EXPIRED");
        verify(metrics).record(eq(RedirectMetrics.Outcome.GONE), anyLong());
    }

    @Test
    void pathsThatCannotBeCodesNeverReachTheService() {
        assertThat(mvc.get().uri("/abc")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/favicon.ico")).hasStatus(HttpStatus.NOT_FOUND);
        verifyNoInteractions(linkService);
    }
}
