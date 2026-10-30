package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code api-key} mode with {@code sdlc.security.public-docs=false} (what the prod profile sets): the OpenAPI document,
 * Swagger UI and the management endpoints other than health need the API key like {@code GET /api/**}, in every
 * spelling of their paths, while the console and the health endpoint (with its probes) stay public.
 */
@SpringBootTest(properties = {"sdlc.security.mode=api-key", "sdlc.security.api-key=" + ApiKeyModeTest.KEY,
        "sdlc.security.public-docs=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyModePrivateDocsTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void theDocumentsNeedTheKey() {
        for (String path : List.of("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui.html", "/swagger-ui/index.html")) {
            MvcTestResult result = mvc.get().uri(path).exchange();
            assertThat(result).as(path).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).as(path)
                    .isEqualTo(ApiKeyAuthFilter.CHALLENGE);
        }
        assertThat(mvc.get().uri("/v3/api-docs").header(ApiKeyAuthFilter.HEADER, ApiKeyModeTest.KEY).exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void theManagementEndpointsOtherThanHealthNeedTheKey() {
        for (String path : List.of("/actuator", "/actuator/metrics", "/actuator/metrics/jvm.threads.live",
                "/actuator/info", "/ACTUATOR/metrics", "/actuator/health/../metrics")) {
            assertThat(mvc.get().uri(path).exchange()).as(path).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        assertThat(mvc.get().uri("/actuator/metrics").header(ApiKeyAuthFilter.HEADER, ApiKeyModeTest.KEY)
                .exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void theConsoleAndHealthStayPublic() {
        for (String path : List.of("/", "/index.html", "/js/api.js", "/actuator/health", "/actuator/health/liveness")) {
            assertThat(mvc.get().uri(path).exchange()).as(path).hasStatus(HttpStatus.OK);
        }
    }
}
