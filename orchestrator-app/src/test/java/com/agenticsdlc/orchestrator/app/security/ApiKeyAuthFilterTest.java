package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ApiKeyAuthFilter}: protects {@code /api/**} only and answers a 401 Problem Detail.
 */
class ApiKeyAuthFilterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter("s3cret-key", MAPPER);

    @Test
    void aMatchingKeyPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/runs", "s3cret-key"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aMissingOrWrongKeyIs401ProblemDetail() throws Exception {
        for (String key : new String[] {null, "", "wrong", "s3cret-key "}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("/api/v1/runs", key), response, chain);

            assertThat(chain.getRequest()).as("key %s", key).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(ApiKeyAuthFilter.CHALLENGE)
                    .contains("header=\"X-Api-Key\"");
            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            JsonNode body = MAPPER.readTree(response.getContentAsString());
            assertThat(body.path("status").asInt()).isEqualTo(401);
            assertThat(body.path("title").asString()).isEqualTo("Unauthorized");
            assertThat(body.path("detail").asString()).contains(ApiKeyAuthFilter.HEADER);
            assertThat(response.getContentAsString()).doesNotContain("s3cret-key");
        }
    }

    @Test
    void consoleActuatorAndOpenApiAreNeverProtected() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/js/api.js", "/actuator/health", "/v3/api-docs",
                "/swagger-ui.html"}) {
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request(path, null), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(path).isNotNull();
        }
    }

    @Test
    void encodedParameterizedAndDotSegmentVariantsOfApiPathsAreProtected() throws Exception {
        for (String uri : new String[] {"/%61pi/v1/runs", "/api;x=1/v1/runs", "/api/v1//runs", "//api/v1/runs",
                "/API/v1/runs", "/static/../api/v1/runs", "/api", "/%41%50%49/v1/runs", "/api%2Fv1/runs"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request(uri, null), response, chain);

            assertThat(chain.getRequest()).as(uri).isNull();
            assertThat(response.getStatus()).as(uri).isEqualTo(401);
        }
    }

    @Test
    void theDecodedServletPathDecidesEvenWhenTheRawUriLooksHarmless() throws Exception {
        MockHttpServletRequest request = request("/console", null);
        request.setServletPath("/api/v1/runs");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void lookalikePathsOutsideTheApiAreNotProtected() throws Exception {
        for (String uri : new String[] {"/apidocs", "/api-v1", "/v3/api-docs", "/swagger-ui/index.html",
                "/js/api.js"}) {
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request(uri, null), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(uri).isNotNull();
        }
    }

    @Test
    void withPrivateDocumentsEverySpellingOfTheDocumentPathsNeedsTheKey() throws Exception {
        ApiKeyAuthFilter privateDocs = new ApiKeyAuthFilter("s3cret-key", MAPPER, true);
        for (String uri : new String[] {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/V3/API-DOCS", "/v3;x=1/api-docs", "//v3//api-docs", "/swagger-ui.html", "/swagger-ui/index.html",
                "/static/../swagger-ui/index.html", "/%73wagger-ui/index.html", "/api/v1/runs"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            privateDocs.doFilter(request(uri, null), response, chain);

            assertThat(chain.getRequest()).as(uri).isNull();
            assertThat(response.getStatus()).as(uri).isEqualTo(401);
            assertThat(response.getContentAsString()).as(uri).contains("API documentation");
        }
        MockFilterChain withKey = new MockFilterChain();
        privateDocs.doFilter(request("/v3/api-docs", "s3cret-key"), new MockHttpServletResponse(), withKey);
        assertThat(withKey.getRequest()).isNotNull();
    }

    @Test
    void withPrivateDocumentsTheManagementEndpointsOtherThanHealthNeedTheKey() throws Exception {
        ApiKeyAuthFilter privateDocs = new ApiKeyAuthFilter("s3cret-key", MAPPER, true);
        for (String uri : new String[] {"/actuator", "/actuator/", "/actuator/metrics", "/actuator/info",
                "/ACTUATOR/Metrics", "/actuator;x=1/metrics", "//actuator//metrics", "/actuator/health/../metrics",
                "/%61ctuator/metrics", "/actuator/healthx"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            privateDocs.doFilter(request(uri, null), response, chain);

            assertThat(chain.getRequest()).as(uri).isNull();
            assertThat(response.getStatus()).as(uri).isEqualTo(401);
        }
        for (String uri : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            MockFilterChain chain = new MockFilterChain();

            privateDocs.doFilter(request(uri, null), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(uri).isNotNull();
        }
        MockFilterChain publicDocs = new MockFilterChain();
        filter.doFilter(request("/actuator/metrics", null), new MockHttpServletResponse(), publicDocs);
        assertThat(publicDocs.getRequest()).as("public documents: the actuator stays public too").isNotNull();
    }

    @Test
    void withPrivateDocumentsTheConsoleHealthAndLookalikesStayPublic() throws Exception {
        ApiKeyAuthFilter privateDocs = new ApiKeyAuthFilter("s3cret-key", MAPPER, true);
        for (String uri : new String[] {"/", "/index.html", "/js/api.js", "/actuator/health", "/v3/api-docsx",
                "/swagger-uix"}) {
            MockFilterChain chain = new MockFilterChain();

            privateDocs.doFilter(request(uri, null), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(uri).isNotNull();
        }
    }

    @Test
    void normalizationStripsParametersDuplicateSlashesAndDotSegments() {
        assertThat(ApiKeyAuthFilter.normalize("/api;x=1/v1;y=2/runs")).isEqualTo("/api/v1/runs");
        assertThat(ApiKeyAuthFilter.normalize("//api///v1//runs")).isEqualTo("/api/v1/runs");
        assertThat(ApiKeyAuthFilter.normalize("/a/./b/../../api/v1")).isEqualTo("/api/v1");
        assertThat(ApiKeyAuthFilter.normalize("/../api/v1")).isEqualTo("/api/v1");
        assertThat(ApiKeyAuthFilter.normalize("")).isEmpty();
    }

    @Test
    void aBlankKeyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new ApiKeyAuthFilter(" ", MAPPER)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest request(String path, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (key != null) {
            request.addHeader(ApiKeyAuthFilter.HEADER, key);
        }
        return request;
    }
}
