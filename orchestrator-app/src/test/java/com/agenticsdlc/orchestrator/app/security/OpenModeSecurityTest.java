package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.app.api.ProblemTypes;

/**
 * {@code open} mode (the default without configuration): the Spring Security chain is in place but every caller
 * holds every role, so the API behaves as before modes existed; the operator is still named by
 * {@code X-Operator-Id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenModeSecurityTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void everyGovernanceRoleIsGrantedWithoutCredentials() {
        assertThat(post("/api/v1/workspace/reindex", "admin.user")).as("ADMIN").hasStatus(HttpStatus.OK);
        assertThat(post("/api/v1/runs/no-such-run/stages/design/approve", "approver.user")).as("APPROVER")
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/runs/no-such-run/resume", "operator.user")).as("OPERATOR")
                .hasStatus(HttpStatus.NOT_FOUND);
        MvcTestResult read = mvc.get().uri("/api/v1/runs").header("X-Api-Key", "ignored-in-open-mode").exchange();
        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(read.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    void theOperatorHeaderIsStillRequired() {
        MvcTestResult result = mvc.post().uri("/api/v1/workspace/reindex").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo(ProblemTypes.OPERATOR_IDENTITY_REQUIRED.toString());
    }

    @Test
    void documentsActuatorAndConsoleArePublic() {
        for (String path : List.of("/", "/app.js", "/v3/api-docs", "/actuator/health", "/actuator/metrics")) {
            assertThat(mvc.get().uri(path).exchange()).as(path).hasStatus(HttpStatus.OK);
        }
    }

    private MvcTestResult post(String path, String operator) {
        return mvc.post().uri(path).header(OperatorIdentityResolver.HEADER, operator)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
    }
}
