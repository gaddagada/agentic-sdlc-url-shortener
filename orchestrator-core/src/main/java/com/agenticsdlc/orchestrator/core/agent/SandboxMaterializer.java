package com.agenticsdlc.orchestrator.core.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Materializes a change set into a workspace sandbox when sandboxes are enabled for a REPO run, and returns the
 * {@code DELETE_SANDBOX} compensation for it. Shared by the deterministic and LLM implementation agents.
 */
public final class SandboxMaterializer {

    private SandboxMaterializer() {
    }

    /**
     * Copies the workspace into {@code <sandboxRoot>/<runId>} and applies the change set, if enabled.
     *
     * @param changeSet the change set
     * @param context the stage context
     * @return the compensation to register, or empty when sandboxes are disabled for this run
     * @throws StageFailure (TRANSIENT) when the sandbox cannot be written
     */
    public static Optional<CompensationRequest> materialize(ChangeSet changeSet, StageContext context)
            throws StageFailure {
        Path workspaceRoot = context.settings().workspaceRoot();
        if (!context.settings().sandboxEnabled() || context.options().workspace() != WorkspaceMode.REPO
                || workspaceRoot == null) {
            return Optional.empty();
        }
        try {
            WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspaceRoot, context.settings().sandboxRoot(),
                    context.runId());
            CompensationRequest compensation = new CompensationRequest(WorkspaceSandbox.DELETE_ACTION,
                    sandbox.path().toString(), "delete the sandbox of run " + context.runId());
            sandbox.apply(changeSet);
            return Optional.of(compensation);
        } catch (IOException | IllegalArgumentException e) {
            throw new StageFailure(FailureKind.TRANSIENT, "could not materialize the sandbox: " + e.getMessage(), e,
                    null);
        }
    }
}
