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
        POST | /api                            | /v1/links;x   | true
        POST | ''                              | /api/v1/links | true
        POST | /api/v1/links/bulk/             | NULL          | true
        POST | /api/v1/links/bulkx             | NULL          | true
        POST | /api/v1/links/other             | NULL          | true
        POST | /api/v1/links/bulk              | NULL          | false
        POST | /api/v1/links/bulk;x            | NULL          | false
        POST | //api/v1/links//bulk            | NULL          | false
        GET  | /api/v1/links                   | NULL          | false
        GET  | /api/v1/links;x                 | NULL          | false
        POST | /api/v1/other                   | NULL          | false
        POST | /abc1234                        | NULL          | false
        POST | /                               | NULL          | false
        """)
    void limitsEveryPostUnderTheLinksPathExceptTheBulkEndpointWhateverItsSpelling(String method, String servletPath,
                                                                                  String pathInfo, boolean limited) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ignored-raw-uri");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);

        assertThat(filter.shouldNotFilter(request)).isEqualTo(!limited);
    }

    @Test
    void pathParameterSpellingSharesTheBucketAndTheProblemInstanceIsNormalized() throws Exception {
        MockHttpServletResponse first = post("/api/v1/links");
        MockHttpServletResponse second = post("/api/v1/links;x");

        assertThat(first.getStatus()).isEqualTo(200); // passed on to the (mock) chain
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        assertThat(second.getContentType()).isEqualTo("application/problem+json");
        assertThat(second.getContentAsString())
                .contains("\"instance\":\"/api/v1/links\"")
                .contains("\"type\":\"https://agenticsdlc.dev/problems/rate-limited\"")
                .contains("\"status\":429");
    }

    @Test
    void aRateLimitedDecodedPathWithCharactersIllegalInAUriStillGetsThe429() throws Exception {
        post("/api/v1/links");

        MockHttpServletResponse limited = post("/api/v1/links/a b%c");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("\"instance\":\"/api/v1/links/a%20b%25c\"");
    }

    @Test
    void ipv6ClientsShareTheBucketOfTheirSlash64() throws Exception {
        MockHttpServletResponse first = post("/api/v1/links", "2001:db8:1:2::10");
        MockHttpServletResponse otherAddressSameNetwork = post("/api/v1/links", "2001:db8:1:2:ffff:1:2:3");
        MockHttpServletResponse otherNetwork = post("/api/v1/links", "2001:db8:1:3::10");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(otherAddressSameNetwork.getStatus()).as("a new address of the same /64 is the same client")
                .isEqualTo(429);
        assertThat(otherNetwork.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse post(String path) throws Exception {
        return post(path, "192.0.2.10");
    }

    private MockHttpServletResponse post(String path, String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
