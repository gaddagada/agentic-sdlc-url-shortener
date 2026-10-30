package com.agenticsdlc.orchestrator.app.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.WorkspaceIndexView;
import com.agenticsdlc.orchestrator.app.config.WorkspaceIndexHolder;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The codebase index of the repository workspace (what brownfield impact analysis reasons over).
 */
@RestController
@RequestMapping("/api/v1/workspace")
@Tag(name = "Workspace", description = "Codebase index of the repository workspace")
public class WorkspaceController {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceIndexHolder workspace;

    /**
     * Creates the controller.
     *
     * @param workspace workspace index holder
     */
    public WorkspaceController(WorkspaceIndexHolder workspace) {
        this.workspace = workspace;
    }

    /**
     * Returns the current index.
     *
     * @return the index view
     */
    @GetMapping("/index")
    @Operation(summary = "Get the current codebase index", operationId = "getWorkspaceIndex")
    public WorkspaceIndexView index() {
        return WorkspaceIndexView.from(workspace.root(), workspace.enabled(), workspace.current(),
                workspace.lastError());
    }

    /**
     * Rebuilds the index from disk.
     *
     * @param operator operator from {@code X-Operator-Id}
     * @return the new index view
     */
    @PostMapping("/reindex")
    @Operation(summary = "Rebuild the codebase index", operationId = "reindexWorkspace")
    public WorkspaceIndexView reindex(@OperatorId Actor operator) {
        LOG.info("workspace re-index requested operator={}", operator.id());
        CodebaseIndex index = workspace.reindex();
        return WorkspaceIndexView.from(workspace.root(), workspace.enabled(), workspace.enabled() ? index : null,
                workspace.lastError());
    }
}
