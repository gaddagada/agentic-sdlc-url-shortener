package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.shortener.api.RedirectController;
import com.agenticsdlc.shortener.api.RedirectMetrics;
import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.service.LinkService;
import com.agenticsdlc.shortener.service.RedirectTarget;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;
import com.agenticsdlc.shortener.support.MutableClock;

@WebMvcTest(RedirectController.class)
@Import(SecurityHeadersFilterTest.Config.class)
class SecurityHeadersFilterTest {

    private static final URI TARGET = URI.create("https://example.com/landing");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LinkService linkService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private RedirectMetrics metrics;

    @Test
    void redirectStillWorksAndCarriesTheSecurityHeaders() {
        when(linkService.resolveRedirect("abc1234")).thenReturn(new RedirectTarget(42L, TARGET));

        MvcTestResult result = mvc.get().uri("/abc1234").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, TARGET.toString())
                .hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertSecurityHeaders(result);
    }

    @Test
    void problemResponsesCarryTheSecurityHeadersAndKeepNoStore() {
        when(linkService.resolveRedirect("missing1")).thenThrow(new LinkNotFoundException("missing1"));

        MvcTestResult result = mvc.get().uri("/missing1").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertSecurityHeaders(result);
    }

    @Test
    void unmappedPathsCarryTheSecurityHeaders() {
        MvcTestResult result = mvc.get().uri("/favicon.ico").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertSecurityHeaders(result);
    }

    private static void assertSecurityHeaders(MvcTestResult result) {
        assertThat(result).hasHeader(SecurityHeadersFilter.CONTENT_TYPE_OPTIONS, "nosniff")
                .hasHeader(SecurityHeadersFilter.REFERRER_POLICY, "no-referrer")
                .hasHeader(SecurityHeadersFilter.FRAME_OPTIONS, "DENY");
    }
}
