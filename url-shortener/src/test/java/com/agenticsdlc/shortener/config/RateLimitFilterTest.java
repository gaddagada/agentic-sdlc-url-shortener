package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.support.MutableClock;
import com.agenticsdlc.shortener.support.TestProperties;

import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new TokenBucketRateLimiter(1, 1, new MutableClock(Instant.parse("2026-01-15T10:00:00Z"))),
            new ClientIpResolver(TestProperties.defaults()), JsonMapper.builder().build());

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
        POST | /api/v1/links                   | NULL          | true
        POST | /api/v1/links;x                 | NULL          | true
        POST | /api/v1;v=1/links;jsessionid=1  | NULL          | true
        POST | //api//v1//links                | NULL          | true
        POST | /api/v1/links/bulk              | NULL          | true
        POST | /api                            | /v1/links;x   | true
        POST | ''                              | /api/v1/links | true
        GET  | /api/v1/links                   | NULL          | false
        GET  | /api/v1/links;x                 | NULL          | false
        POST | /api/v1/other                   | NULL          | false
        POST | /abc1234                        | NULL          | false
        POST | /                               | NULL          | false
        """)
    void limitsEveryPostUnderTheLinksPathWhateverItsSpelling(String method, String servletPath, String pathInfo,
                                                             boolean limited) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ignored-raw-uri");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);

        assertThat(filter.shouldNotFilter(request)).isEqualTo(!limited);
    }

    @Test
    void normalizesPathParametersAndDuplicateSlashes() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ignored");
        request.setServletPath("//api;a=b//v1/links;x/");

        assertThat(RateLimitFilter.normalizedPath(request)).isEqualTo("/api/v1/links/");
    }

    @Test
    void pathParameterSpellingSharesTheBucketAndTheProblemInstanceIsNormalized() throws Exception {
        MockHttpServletResponse first = post("/api/v1/links");
        MockHttpServletResponse second = post("/api/v1/links;x");

        assertThat(first.getStatus()).isEqualTo(200); // passed on to the (mock) chain
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");
        assertThat(second.getContentAsString()).contains("\"instance\":\"/api/v1/links\"");
    }

    private MockHttpServletResponse post(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
