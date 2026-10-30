package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.config.WorkspaceIndexHolder;
import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuiteRunner;

/**
 * {@link EvaluationController} with {@code sdlc.evaluation.enabled=false} (the prod profile): the suite endpoint is
 * 404 feature-disabled and never builds an engine; it requires the operator header like every POST.
 */
@WebMvcTest(EvaluationController.class)
@Import(SecuritySliceConfiguration.class)
@TestPropertySource(properties = "sdlc.evaluation.enabled=false")
class EvaluationControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrchestrationEngine engine;

    @MockitoBean
    private WorkspaceIndexHolder workspace;

    @MockitoBean
    private EvaluationSuiteRunner.EngineFactory engines;

    @Test
    void aDisabledSuiteIs404FeatureDisabled() {
        MvcTestResult result = mvc.post().uri("/api/v1/evaluation/suite/run")
                .header(OperatorIdentityResolver.HEADER, "admin").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.FEATURE_DISABLED.toString());
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("sdlc.evaluation.enabled");
        verifyNoInteractions(engines);
    }

    @Test
    void theSuiteNeedsTheOperatorHeader() {
        assertThat(mvc.post().uri("/api/v1/evaluation/suite/run").exchange()).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.OPERATOR_IDENTITY_REQUIRED.toString());
    }
}
