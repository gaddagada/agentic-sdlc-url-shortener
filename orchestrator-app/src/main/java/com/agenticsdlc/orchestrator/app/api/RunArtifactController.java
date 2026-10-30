package com.agenticsdlc.orchestrator.app.api;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ArtifactSummaryView;
import com.agenticsdlc.orchestrator.app.api.dto.ArtifactView;
import com.agenticsdlc.orchestrator.app.api.dto.AuditEventView;
import com.agenticsdlc.orchestrator.app.api.dto.LineageView;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.model.Run;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Read-only evidence of a run: artifacts (with payload and Markdown), artifact lineage and the audit trail.
 */
@RestController
@RequestMapping("/api/v1/runs/{id}")
@Tag(name = "Run evidence", description = "Artifacts, lineage and audit trail of a run")
public class RunArtifactController {

    private final OrchestrationEngine engine;
    private final AuditSink auditSink;
    private final ArtifactViewMapper mapper;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     * @param auditSink the audit sink
     * @param mapper artifact view mapper
     */
    public RunArtifactController(OrchestrationEngine engine, AuditSink auditSink, ArtifactViewMapper mapper) {
        this.engine = engine;
        this.auditSink = auditSink;
        this.mapper = mapper;
    }

    /**
     * Lists every artifact version of a run.
     *
     * @param id run id
     * @return summaries in creation order
     */
    @GetMapping("/artifacts")
    @Operation(summary = "List the artifact versions of a run", operationId = "listArtifacts")
    public List<ArtifactSummaryView> artifacts(@PathVariable String id) {
        return mapper.summaries(engine.get(id));
    }

    /**
     * Returns one artifact version of a stage.
     *
     * @param id run id
     * @param stageId stage id
     * @param version version, or null for the accepted version
     * @return the artifact
     */
    @GetMapping("/artifacts/{stageId}")
    @Operation(summary = "Get an artifact of a stage (accepted version by default)", operationId = "getArtifact")
    public ArtifactView artifact(@PathVariable String id, @PathVariable String stageId,
                                 @RequestParam(required = false) @Nullable @Min(1) Integer version) {
        Run run = engine.get(id);
        Artifact artifact = (version == null ? run.acceptedArtifact(stageId) : run.artifact(stageId, version))
                .orElseThrow(() -> new ArtifactNotFoundException(version == null
                        ? "Run " + id + " has no accepted artifact for stage " + stageId
                        : "Run " + id + " has no artifact version " + version + " for stage " + stageId));
        return mapper.view(run, artifact);
    }

    /**
     * Returns the artifact lineage of a run.
     *
     * @param id run id
     * @return the lineage
     */
    @GetMapping("/lineage")
    @Operation(summary = "Get the artifact lineage of a run", operationId = "getLineage")
    public LineageView lineage(@PathVariable String id) {
        return mapper.lineage(engine.get(id));
    }

    /**
     * Returns audit events of a run in sequence order.
     *
     * @param id run id
     * @param afterSeq only events with a greater sequence number
     * @param limit maximum number of events
     * @return events
     */
    @GetMapping("/audit")
    @Operation(summary = "Get the audit trail of a run", operationId = "getAudit")
    public List<AuditEventView> audit(@PathVariable String id,
                                      @RequestParam(defaultValue = "0") @Min(0) long afterSeq,
                                      @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        engine.get(id);
        return auditSink.forRun(id, afterSeq).stream().limit(limit).map(AuditEventView::from).toList();
    }
}
