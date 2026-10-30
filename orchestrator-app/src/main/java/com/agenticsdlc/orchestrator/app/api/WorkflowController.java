package com.agenticsdlc.orchestrator.app.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.WorkflowView;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The workflow definition the engine executes.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflow", description = "Workflow definition")
public class WorkflowController {

    private final OrchestrationEngine engine;

    /**
     * Creates the controller.
     *
     * @param engine the engine
     */
    public WorkflowController(OrchestrationEngine engine) {
        this.engine = engine;
    }

    /**
     * Returns the default SDLC workflow.
     *
     * @return the workflow
     */
    @GetMapping("/default")
    @Operation(summary = "Get the default SDLC workflow", operationId = "getDefaultWorkflow")
    public WorkflowView defaultWorkflow() {
        return WorkflowView.from(engine.workflowGraph());
    }
}
