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
import com.agenticsdlc.orchestrator.app.api.dto.AuditIntegrityView;
import com.agenticsdlc.orchestrator.app.api.dto.LineageView;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.model.Run;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Read-only evidence of a run: artifacts (with payload, Markdown and, for release readiness, the change record
 * signature), artifact lineage, the audit trail and the verification of its hash chain.
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
    @ProblemResponses(404)
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
    @Operation(summary = "Get an artifact of a stage (accepted version by default)", operationId = "getArtifact",
            description = "Without a version the accepted version is returned; a version under review is not "
                    + "accepted until its exit gate is approved, so request it by version "
                    + "(pendingActions[].reviewVersion).")
    @ProblemResponses({400, 404})
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
    @ProblemResponses(404)
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
    @Operation(summary = "Get the audit trail of a run", operationId = "getAudit",
            description = "A page of at most limit events with seq > afterSeq, in sequence order; continue with the "
                    + "seq of the last event.")
    @ProblemResponses({400, 404})
    public List<AuditEventView> audit(@PathVariable String id,
                                      @RequestParam(defaultValue = "0") @Min(0) long afterSeq,
                                      @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        engine.get(id);
        return auditSink.forRun(id, afterSeq, limit).stream().map(AuditEventView::from).toList();
    }

    /**
     * Verifies the hash chain of a run's stored audit trail.
     *
     * @param id run id
     * @return the verification result
     */
    @GetMapping("/audit/integrity")
    @Operation(summary = "Verify the hash chain of a run's audit trail", operationId = "getAuditIntegrity",
            description = "Reads every stored audit event of the run and verifies the per-run SHA-256 hash chain: "
                    + "events signed, in sequence, linked to their predecessor and unmodified. A broken chain names "
                    + "the first problematic seq; events written before hash chaining existed report the chain as "
                    + "unsigned.")
    @ProblemResponses(404)
    public AuditIntegrityView auditIntegrity(@PathVariable String id) {
        engine.get(id);
        return AuditIntegrityView.from(AuditChain.verify(auditSink.forRun(id, 0)));
    }

}
