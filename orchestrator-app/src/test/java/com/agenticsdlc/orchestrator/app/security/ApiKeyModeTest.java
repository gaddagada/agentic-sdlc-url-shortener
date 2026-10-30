package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.app.api.ProblemTypes;

/**
 * {@code sdlc.security.mode=api-key} selected explicitly behaves like the key-only configuration of
 * {@link ApiKeyAuthFilterServletTest}: 401 Problem Detail with a challenge without the key, every role with it, the
 * operator still named by {@code X-Operator-Id}, no JWT decoder in the context.
 */
@SpringBootTest(properties = {"sdlc.security.mode=api-key", "sdlc.security.api-key=" + ApiKeyModeTest.KEY})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyModeTest {

    static final String KEY = "an-api-key-of-at-least-32-characters!";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void withoutTheKeyTheApiIs401() {
        MvcTestResult result = mvc.get().uri("/api/v1/runs").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(ApiKeyAuthFilter.CHALLENGE);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.UNAUTHORIZED.toString());
        assertThat(mvc.get().uri("/api/v1/runs").header(ApiKeyAuthFilter.HEADER, "wrong").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void withTheKeyEveryRoleIsGrantedAndTheOperatorHeaderApplies() {
        assertThat(mvc.get().uri("/api/v1/runs").header(ApiKeyAuthFilter.HEADER, KEY).exchange())
                .hasStatus(HttpStatus.OK);
        assertThat(mvc.post().uri("/api/v1/workspace/reindex").header(ApiKeyAuthFilter.HEADER, KEY)
                .header(OperatorIdentityResolver.HEADER, "admin.user").exchange()).hasStatus(HttpStatus.OK);
        assertThat(mvc.post().uri("/api/v1/runs/no-such-run/stages/design/reject").header(ApiKeyAuthFilter.HEADER, KEY)
                .header(OperatorIdentityResolver.HEADER, "approver.user").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"no\", \"disposition\": \"ABORT\"}").exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/v1/workspace/reindex").header(ApiKeyAuthFilter.HEADER, KEY).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void consoleHealthAndDocumentsNeedNoKey() {
        for (String path : List.of("/", "/index.html", "/js/api.js", "/actuator/health", "/v3/api-docs")) {
            assertThat(mvc.get().uri(path).exchange()).as(path).hasStatus(HttpStatus.OK);
        }
    }

    @Test
    void noJwtDecoderIsCreatedOutsideOidcMode() {
        assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
    }
}
