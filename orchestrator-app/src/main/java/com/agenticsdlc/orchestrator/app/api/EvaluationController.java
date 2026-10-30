package com.agenticsdlc.orchestrator.app.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.EvaluationReportView;
import com.agenticsdlc.orchestrator.app.api.dto.SuiteResultView;
import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.app.config.WorkspaceIndexHolder;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationReport;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuite;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuiteRunner;
import com.agenticsdlc.orchestrator.core.evaluation.RunEvaluator;
import com.agenticsdlc.orchestrator.core.evaluation.SuiteResult;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Evaluation of agent output quality: the deterministic scores of one run's artifacts, and (ADMIN, when
 * {@code sdlc.evaluation.enabled}) a run of the bundled evaluation suite on isolated in-memory engines.
 */
@RestController
@Tag(name = "Evaluation", description = "Artifact quality scores and the evaluation suite")
public class EvaluationController {

    /** Media type of the Markdown rendering. */
    static final MediaType TEXT_MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationController.class);

    private final OrchestrationEngine engine;
    private final WorkspaceIndexHolder workspace;
    private final EvaluationSuiteRunner.EngineFactory evaluationEngines;
    private final boolean suiteEnabled;
    private final ReentrantLock suiteRunning = new ReentrantLock();

    /**
     * Creates the controller.
     *
     * @param engine the production engine (read only: runs are evaluated, never changed)
     * @param workspace the workspace index (codebase criteria of REPO runs)
     * @param evaluationEngines factory of the isolated engines of suite runs
     * @param properties configuration ({@code sdlc.evaluation.enabled})
     */
    public EvaluationController(OrchestrationEngine engine, WorkspaceIndexHolder workspace,
                                EvaluationSuiteRunner.EngineFactory evaluationEngines,
                                OrchestratorProperties properties) {
        this.engine = engine;
        this.workspace = workspace;
        this.evaluationEngines = evaluationEngines;
        this.suiteEnabled = properties.evaluation().enabled();
    }

    /**
     * Evaluates the artifacts of a run.
     *
     * @param id run id
     * @param format {@code json} (default) or {@code markdown}
     * @return the report as JSON, or as {@code text/markdown}
     */
    @GetMapping("/api/v1/runs/{id}/evaluation")
    @Operation(summary = "Evaluate the artifacts of a run", operationId = "getRunEvaluation",
            description = "Scores the accepted artifact of every stage (else the latest one of the current plan, "
                    + "noted as under review) with deterministic criteria: per stage a score within 0..1 and every "
                    + "criterion with weight, value and rationale, plus the overall mean. format=markdown returns the "
                    + "same report as text/markdown.",
            responses = @ApiResponse(responseCode = "200", description = "The evaluation", content = {
                    @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EvaluationReportView.class)),
                    @Content(mediaType = "text/markdown", schema = @Schema(type = "string"))}))
    @ProblemResponses({400, 404})
    public ResponseEntity<?> evaluation(@PathVariable String id,
                                        @Parameter(description = "json (default) or markdown")
                                        @RequestParam(defaultValue = "json") String format) {
        String requested = format.strip().toLowerCase(Locale.ROOT);
        if (!requested.equals("json") && !requested.equals("markdown")) {
            throw new InvalidRequestException("format must be json or markdown, got '" + format.strip() + "'");
        }
        Run run = engine.get(id);
        EvaluationReport report = RunEvaluator.evaluate(run,
                run.options().workspace() == WorkspaceMode.REPO ? workspace.current() : null);
        if (requested.equals("markdown")) {
            return ResponseEntity.ok().contentType(TEXT_MARKDOWN).body(report.markdown());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(EvaluationReportView.from(report));
    }

    /**
     * Runs the bundled evaluation suite with the deterministic agents on isolated in-memory engines.
     *
     * @param operator operator from {@code X-Operator-Id} (logged)
     * @param provider agent provider; only {@code deterministic}
     * @return the suite result
     */
    @PostMapping("/api/v1/evaluation/suite/run")
    @Operation(summary = "Run the bundled evaluation suite (deterministic agents, isolated engine)",
            operationId = "runEvaluationSuite",
            description = "Runs every case of the core's bundled suite on a fresh in-memory engine per case "
                    + "(deterministic agents, in-memory store, audit sink and leases; the production engine, store, "
                    + "audit trail and metrics are never touched). The runner answers clarifications with the first "
                    + "suggested answer and approves gates as the operator identity evaluation-runner (never a NO_GO "
                    + "release). Only provider=deterministic: LLM-backed suite runs are an offline job. Disabled "
                    + "(404 feature-disabled) unless sdlc.evaluation.enabled=true; one suite run at a time (409).")
    @ProblemResponses({400, 404, 409})
    public SuiteResultView runSuite(@OperatorId Actor operator,
                                    @Parameter(description = "Agent provider; only deterministic is supported")
                                    @RequestParam(defaultValue = AgentRegistry.DETERMINISTIC) String provider) {
        if (!suiteEnabled) {
            throw new FeatureDisabledException("The evaluation suite endpoint is disabled in this deployment; set "
                    + "sdlc.evaluation.enabled=true to enable it (the local profile does).");
        }
        if (!AgentRegistry.DETERMINISTIC.equals(provider.strip())) {
            throw new InvalidRequestException("provider '" + provider.strip() + "' is not supported here: the "
                    + "endpoint runs the suite with the deterministic agents only. LLM-backed suite runs call the "
                    + "model for every stage of every case and are an offline job (run EvaluationSuiteRunner from CI "
                    + "or a dedicated evaluation deployment).");
        }
        if (!suiteRunning.tryLock()) {
            throw new EvaluationInProgressException();
        }
        try {
            EvaluationSuite suite = EvaluationSuite.loadDefault();
            LOG.info("evaluation suite started suite={} version={} cases={} provider={} operator={}", suite.name(),
                    suite.version(), suite.cases().size(), AgentRegistry.DETERMINISTIC, operator.id());
            SuiteResult result = new EvaluationSuiteRunner().runSuite(evaluationEngines, suite);
            LOG.info("evaluation suite finished suite={} passed={}/{} operator={}", suite.name(), result.passedCount(),
                    result.cases().size(), operator.id());
            return SuiteResultView.from(result, AgentRegistry.DETERMINISTIC);
        } finally {
            suiteRunning.unlock();
        }
    }
}
