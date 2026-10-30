package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class ApiKeyFilterTest {

    private static final String KEY = "k3y-0f-at-least-32-characters-long!";

    private final ApiKeyFilter filter = new ApiKeyFilter(KEY, JsonMapper.builder().build());

    @ParameterizedTest(name = "{0} {1} -> protected={2}")
    @CsvSource(delimiter = '|', textBlock = """
        POST    | /api/v1/links                     | true
        POST    | /api/v1/links/bulk                | true
        GET     | /api/v1/links/abc1234             | true
        HEAD    | /api/v1/links/abc1234             | true
        DELETE  | /api/v1/links/abc1234             | true
        GET     | /api/v1/links/abc1234/analytics   | true
        PUT     | /api/v1/links/abc1234             | true
        POST    | /api/v1/links;x                   | true
        GET     | //api//v1/links/abc1234           | true
        GET     | /api;v=1/v1/links/abc1234         | true
        OPTIONS | /api/v1/links                     | false
        GET     | /abc1234                          | false
        HEAD    | /abc1234                          | false
        GET     | /actuator/health                  | false
        GET     | /v3/api-docs                      | false
        GET     | /swagger-ui.html                  | false
        GET     | /api/v1/other                     | false
        """)
    void protectsEveryNonOptionsRequestUnderTheLinksPathWhateverItsSpelling(String method, String servletPath,
                                                                           boolean protectedPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ignored-raw-uri");
        request.setServletPath(servletPath);

        assertThat(filter.shouldNotFilter(request)).isEqualTo(!protectedPath);
    }

    @Test
    void withoutAConfiguredKeyNothingIsProtected() {
        ApiKeyFilter open = new ApiKeyFilter("  ", JsonMapper.builder().build());
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/links/abc1234");
        request.setServletPath("/api/v1/links/abc1234");

        assertThat(open.isEnforcing()).isFalse();
        assertThat(new ApiKeyFilter(null, JsonMapper.builder().build()).isEnforcing()).isFalse();
        assertThat(open.shouldNotFilter(request)).isTrue();
        assertThat(filter.isEnforcing()).isTrue();
    }

    @Test
    void theRightKeyPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = send(KEY, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aMissingAndAWrongKeyGetTheSame401ProblemWithoutReachingTheApplication() throws Exception {
        MockFilterChain missingChain = new MockFilterChain();
        MockFilterChain wrongChain = new MockFilterChain();

        MockHttpServletResponse missing = send(null, missingChain);
        MockHttpServletResponse wrong = send(KEY + "x", wrongChain);

        for (MockHttpServletResponse response : new MockHttpServletResponse[] {missing, wrong}) {
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(ApiKeyFilter.WWW_AUTHENTICATE_VALUE);
            assertThat(response.getContentType()).isEqualTo("application/problem+json");
            assertThat(response.getContentAsString())
                    .contains("\"type\":\"https://agenticsdlc.dev/problems/unauthorized\"")
                    .contains("\"status\":401")
                    .contains("\"instance\":\"/api/v1/links/abc1234\"")
                    .doesNotContain(KEY);
        }
        assertThat(missing.getContentAsString()).isEqualTo(wrong.getContentAsString());
        assertThat(missingChain.getRequest()).isNull();
        assertThat(wrongChain.getRequest()).isNull();
    }

    @Test
    void comparesTheWholeKey() {
        assertThat(filter.matches(KEY)).isTrue();
        assertThat(filter.matches(KEY.substring(0, KEY.length() - 1))).isFalse();
        assertThat(filter.matches(KEY + KEY)).isFalse();
        assertThat(filter.matches(KEY.toUpperCase(Locale.ROOT))).isFalse();
        assertThat(filter.matches("")).isFalse();
        assertThat(filter.matches(null)).isFalse();
    }

    @Test
    void ignoresWhitespaceAroundTheConfiguredKey() {
        ApiKeyFilter fromSecretFile = new ApiKeyFilter("  " + KEY + "\n", JsonMapper.builder().build());

        assertThat(fromSecretFile.matches(KEY)).isTrue();
    }

    private MockHttpServletResponse send(String apiKey, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/links/abc1234");
        request.setServletPath("/api/v1/links/abc1234");
        if (apiKey != null) {
            request.addHeader("X-Api-Key", apiKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
