package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ApproveRequest;
import com.agenticsdlc.orchestrator.app.api.dto.ClarifyRequest;
import com.agenticsdlc.orchestrator.app.api.dto.CreateRunRequest;
import com.agenticsdlc.orchestrator.app.api.dto.GraphView;
import com.agenticsdlc.orchestrator.app.api.dto.RejectRequest;
import com.agenticsdlc.orchestrator.app.api.dto.ReplanRequest;
import com.agenticsdlc.orchestrator.app.api.dto.RunPageView;
import com.agenticsdlc.orchestrator.app.api.dto.RunView;
import com.agenticsdlc.orchestrator.app.api.dto.StopRequest;
import com.agenticsdlc.orchestrator.app.runs.RunListPage;
import com.agenticsdlc.orchestrator.app.runs.RunListQuery;
import com.agenticsdlc.orchestrator.app.runs.RunListSort;
import com.agenticsdlc.orchestrator.app.runs.RunListing;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Runs: start, list, inspect and govern (approve, reject, clarify, re-plan, stop, resume). Every POST requires the
 * {@code X-Operator-Id} header; governance commands are executed by the engine with that operator as the human
 * actor and recorded in the audit trail. Approvals and rejections may name the {@code approvalId} the operator
 * reviewed: the engine then refuses (409) a decision on any other pending approval. Approving the release is
 * subject to the four-eyes rule when {@code sdlc.governance.four-eyes=true}.
 *
 * <p>The runs list is paged and filterable and is read from the listing columns of the {@code runs} table through
 * {@link RunListing} (never from run snapshots or the engine's full listing).</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Runs", description = "Run lifecycle and governance commands")
public class RunController {

    private final OrchestrationEngine engine;
    private final RunLauncher launcher;
    private final RunViewMapper mapper;
    private final FourEyesGuard fourEyes;
    private final RunListing listing;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     * @param launcher run launcher
     * @param mapper view mapper
     * @param fourEyes the four-eyes rule
     * @param listing the paged runs list
     */
    public RunController(OrchestrationEngine engine, RunLauncher launcher, RunViewMapper mapper,
                         FourEyesGuard fourEyes, RunListing listing) {
        this.engine = engine;
        this.launcher = launcher;
        this.mapper = mapper;
        this.fourEyes = fourEyes;
        this.listing = listing;
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
     * Lists one page of runs, read from the listing columns of the {@code runs} table.
     *
     * @param page zero-based page number
     * @param size page size (1 to 200)
     * @param status run statuses to include (repeat the parameter or separate with commas), all when absent
     * @param scenario scenario key to match, or null
     * @param startedBy operator id to match, or null
     * @param sort {@code <field>[,asc|desc]}
     * @return the page
     */
    @GetMapping
    @Operation(summary = "List runs (paged and filterable)", operationId = "listRuns",
            description = "Returns {items, page, size, total, totalPages, sort}. Items are RunSummaryView rows read "
                    + "from the runs table (no run snapshot is read): pendingActions and progress are null for rows "
                    + "not saved since the column was added. Filters are exact matches combined with AND: status "
                    + "(RUNNING, WAITING_HUMAN, SAFE_STOPPED, COMPLETED, ABORTED; repeatable or comma separated), "
                    + "scenario, startedBy. sort is <field>[,asc|desc] with field one of createdAt, updatedAt, "
                    + "endedAt, status, scenario, startedBy (ties broken by id); any other field is a 400. size is "
                    + "1..200. Breaking change: this operation returned a bare array before the paged envelope.")
    @ProblemResponses(400)
    public RunPageView list(@RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "" + RunListQuery.DEFAULT_SIZE) @Min(1)
                            @Max(RunListQuery.MAX_SIZE) int size,
                            @RequestParam(required = false) @Nullable List<String> status,
                            @RequestParam(required = false) @Nullable String scenario,
                            @RequestParam(required = false) @Nullable String startedBy,
                            @RequestParam(defaultValue = "updatedAt,desc") String sort) {
        RunListSort order;
        try {
            order = RunListSort.parse(sort);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        RunListPage result = listing.page(new RunListQuery(page, size, statuses(status), scenario, startedBy, order));
        return new RunPageView(result.items().stream().map(mapper::toSummary).toList(), result.page(), result.size(),
                result.total(), result.totalPages(), order.toString());
    }

    /** Validates and normalizes the status filter (case-insensitive status names). */
    private static List<String> statuses(@Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> statuses = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String name = value.strip().toUpperCase(Locale.ROOT);
            if (Arrays.stream(RunStatus.values()).noneMatch(s -> s.name().equals(name))) {
                throw new InvalidRequestException("status '" + value.strip() + "' is not a run status; use one of "
                        + Arrays.toString(RunStatus.values()));
            }
            if (!statuses.contains(name)) {
                statuses.add(name);
            }
        }
        return List.copyOf(statuses);
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
