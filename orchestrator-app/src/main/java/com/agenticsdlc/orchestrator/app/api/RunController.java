package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * actor and recorded in the audit trail.
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Runs", description = "Run lifecycle and governance commands")
public class RunController {

    private final OrchestrationEngine engine;
    private final RunLauncher launcher;
    private final RunViewMapper mapper;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     * @param launcher run launcher
     * @param mapper view mapper
     */
    public RunController(OrchestrationEngine engine, RunLauncher launcher, RunViewMapper mapper) {
        this.engine = engine;
        this.launcher = launcher;
        this.mapper = mapper;
    }

    /**
     * Starts a run.
     *
     * @param operator operator from {@code X-Operator-Id}
     * @param request the request
     * @return 201 with the run
     */
    @PostMapping
    @Operation(summary = "Start a run", operationId = "createRun")
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
    public GraphView graph(@PathVariable String id) {
        return mapper.toGraph(engine.get(id));
    }

    /**
     * Approves the pending gate of a stage.
     *
     * @param id run id
     * @param stageId stage id
     * @param operator operator from {@code X-Operator-Id}
     * @param request optional comment
     * @return the run
     */
    @PostMapping("/{id}/stages/{stageId}/approve")
    @Operation(summary = "Approve the pending gate of a stage", operationId = "approveStage")
    public RunView approve(@PathVariable String id, @PathVariable String stageId, @OperatorId Actor operator,
                           @Valid @RequestBody(required = false) @Nullable ApproveRequest request) {
        return mapper.toView(engine.approve(id, stageId, operator, request == null ? null : request.comment()));
    }

    /**
     * Rejects the pending gate of a stage.
     *
     * @param id run id
     * @param stageId stage id
     * @param operator operator from {@code X-Operator-Id}
     * @param request reason and disposition
     * @return the run
     */
    @PostMapping("/{id}/stages/{stageId}/reject")
    @Operation(summary = "Reject the pending gate of a stage (rework or abort)", operationId = "rejectStage")
    public RunView reject(@PathVariable String id, @PathVariable String stageId, @OperatorId Actor operator,
                          @Valid @RequestBody RejectRequest request) {
        return mapper.toView(engine.reject(id, stageId, operator, request.reason(), request.disposition()));
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
    public RunView resume(@PathVariable String id, @OperatorId Actor operator) {
        return mapper.toView(engine.resume(id, operator));
    }
}
