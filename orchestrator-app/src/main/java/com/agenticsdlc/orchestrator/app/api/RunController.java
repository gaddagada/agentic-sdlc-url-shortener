package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ApproveRequest;
import com.agenticsdlc.orchestrator.app.api.dto.ClarifyRequest;
import com.agenticsdlc.orchestrator.app.api.dto.CreateRunRequest;
import com.agenticsdlc.orchestrator.app.api.dto.GraphView;
import com.agenticsdlc.orchestrator.app.api.dto.RejectRequest;
import com.agenticsdlc.orchestrator.app.api.dto.ReplanRequest;
import com.agenticsdlc.orchestrator.app.api.dto.RunSummaryView;
import com.agenticsdlc.orchestrator.app.api.dto.RunView;
import com.agenticsdlc.orchestrator.app.api.dto.StopRequest;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.model.Run;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Runs: start, list, inspect and govern (approve, reject, clarify, re-plan, stop, resume). Every POST requires the
 * {@code X-Operator-Id} header; governance commands are executed by the engine with that operator as the human
 * actor and recorded in the audit trail. Approvals and rejections may name the {@code approvalId} the operator
 * reviewed: the engine then refuses (409) a decision on any other pending approval. Approving the release is
 * subject to the four-eyes rule when {@code sdlc.governance.four-eyes=true}.
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Runs", description = "Run lifecycle and governance commands")
public class RunController {

    private final OrchestrationEngine engine;
    private final RunLauncher launcher;
    private final RunViewMapper mapper;
    private final FourEyesGuard fourEyes;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     * @param launcher run launcher
     * @param mapper view mapper
     * @param fourEyes the four-eyes rule
     */
    public RunController(OrchestrationEngine engine, RunLauncher launcher, RunViewMapper mapper,
                         FourEyesGuard fourEyes) {
        this.engine = engine;
        this.launcher = launcher;
        this.mapper = mapper;
        this.fourEyes = fourEyes;
    }

    /**
     * Starts a run.
     *
     * @param operator operator from {@code X-Operator-Id}
     * @param request the request
     * @return 201 with the run
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a run", operationId = "createRun")
    @ProblemResponses({400, 409, 503})
    public ResponseEntity<RunView> create(@OperatorId Actor operator, @Valid @RequestBody CreateRunRequest request) {
        Run run = launcher.launch(request, operator);
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.id())).body(mapper.toView(run));
    }

    /**
     * Lists the runs, newest first.
     *
     * @return summaries
     */
    @GetMapping
    @Operation(summary = "List runs, newest first", operationId = "listRuns")
    public List<RunSummaryView> list() {
        return engine.list().stream().map(mapper::toSummary).toList();
    }

    /**
     * Returns a run.
     *
     * @param id run id
     * @return the run
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a run", operationId = "getRun")
    @ProblemResponses(404)
    public RunView get(@PathVariable String id) {
        return mapper.toView(engine.get(id));
    }

    /**
     * Returns the pipeline graph of a run.
     *
     * @param id run id
     * @return the graph
     */
    @GetMapping("/{id}/graph")
    @Operation(summary = "Get the pipeline graph of a run", operationId = "getRunGraph")
    @ProblemResponses(404)
    public GraphView graph(@PathVariable String id) {
        return mapper.toGraph(engine.get(id));
    }

    /**
     * Approves the pending gate of a stage.
     *
     * @param id run id
     * @param stageId stage id
     * @param operator operator from {@code X-Operator-Id}
     * @param request optional comment and the reviewed approval id
     * @return the run
     */
    @PostMapping("/{id}/stages/{stageId}/approve")
    @Operation(summary = "Approve the pending gate of a stage", operationId = "approveStage",
            description = "Send the approvalId of the reviewed pending action: a stale id (the stage now awaits "
                    + "another approval, for example after a re-plan or rework) is refused with 409.")
    @ProblemResponses({400, 404, 409, 503})
    public RunView approve(@PathVariable String id, @PathVariable String stageId, @OperatorId Actor operator,
                           @Valid @RequestBody(required = false) @Nullable ApproveRequest request) {
        String comment = request == null ? null : request.comment();
        String expectedApprovalId = request == null ? null : request.approvalId();
        if (fourEyes.enabled()) {
            expectedApprovalId = fourEyes.checkApproval(engine.get(id), stageId, operator, expectedApprovalId);
        }
        return mapper.toView(engine.approve(id, stageId, operator, comment, expectedApprovalId));
    }

    /**
     * Rejects the pending gate of a stage.
     *
     * @param id run id
     * @param stageId stage id
     * @param operator operator from {@code X-Operator-Id}
     * @param request reason, disposition and the reviewed approval id
     * @return the run
     */
    @PostMapping("/{id}/stages/{stageId}/reject")
    @Operation(summary = "Reject the pending gate of a stage (rework or abort)", operationId = "rejectStage",
            description = "Send the approvalId of the reviewed pending action: a stale id is refused with 409.")
    @ProblemResponses({400, 404, 409, 503})
    public RunView reject(@PathVariable String id, @PathVariable String stageId, @OperatorId Actor operator,
                          @Valid @RequestBody RejectRequest request) {
        return mapper.toView(engine.reject(id, stageId, operator, request.reason(), request.disposition(),
                request.approvalId()));
    }

    /**
     * Answers the open clarification questions.
     *
     * @param id run id
     * @param operator operator from {@code X-Operator-Id}
     * @param request answers
     * @return the run
     */
    @PostMapping("/{id}/clarify")
    @Operation(summary = "Answer the open clarification questions", operationId = "clarifyRun")
    @ProblemResponses({400, 404, 409, 503})
    public RunView clarify(@PathVariable String id, @OperatorId Actor operator,
                           @Valid @RequestBody ClarifyRequest request) {
        return mapper.toView(engine.clarify(id, operator, request.answers()));
    }

    /**
     * Re-plans a run with a changed requirement.
     *
     * @param id run id
     * @param operator operator from {@code X-Operator-Id}
     * @param request the changed requirement
     * @return the run
     */
    @PostMapping("/{id}/replan")
    @Operation(summary = "Re-plan with a changed requirement", operationId = "replanRun")
    @ProblemResponses({400, 404, 409, 503})
    public RunView replan(@PathVariable String id, @OperatorId Actor operator,
                          @Valid @RequestBody ReplanRequest request) {
        return mapper.toView(engine.replan(id, operator, request.requirement()));
    }

    /**
     * Stops a run (safe-stop).
     *
     * @param id run id
     * @param operator operator from {@code X-Operator-Id}
     * @param request optional reason
     * @return the run
     */
    @PostMapping("/{id}/stop")
    @Operation(summary = "Stop a run (safe-stop)", operationId = "stopRun")
    @ProblemResponses({400, 404, 409, 503})
    public RunView stop(@PathVariable String id, @OperatorId Actor operator,
                        @Valid @RequestBody(required = false) @Nullable StopRequest request) {
        return mapper.toView(engine.stop(id, operator, request == null ? null : request.reason()));
    }

    /**
     * Resumes a safe-stopped run.
     *
     * @param id run id
     * @param operator operator from {@code X-Operator-Id}
     * @return the run
     */
    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a safe-stopped run", operationId = "resumeRun")
    @ProblemResponses({400, 404, 409, 503})
    public RunView resume(@PathVariable String id, @OperatorId Actor operator) {
        return mapper.toView(engine.resume(id, operator));
    }
}
