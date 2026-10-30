package com.agenticsdlc.orchestrator.core.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.ChangeSetRejectedException;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Materializes a change set into a workspace sandbox when sandboxes are enabled for a REPO run, and returns the
 * {@code DELETE_SANDBOX} compensation for it. Shared by the deterministic and LLM implementation agents.
 */
public final class SandboxMaterializer {

    private SandboxMaterializer() {
    }

    /**
     * Copies the workspace into {@code <sandboxRoot>/<runId>} and applies the change set, if enabled. When the
     * change set cannot be applied, the partially prepared sandbox is deleted again (no compensation is left
     * pending for it).
     *
     * @param changeSet the change set
     * @param context the stage context
     * @return the compensation to register, or empty when sandboxes are disabled for this run
     * @throws StageFailure INVALID_OUTPUT when the sandbox rejects the change set (for example a CREATE of an
     *                      existing file, a path outside the allowed roots or a DIFF hunk that does not match the
     *                      file; the message is fed back to the agent), TRANSIENT when the sandbox cannot be written
     */
    public static Optional<CompensationRequest> materialize(ChangeSet changeSet, StageContext context)
            throws StageFailure {
        Path workspaceRoot = context.settings().workspaceRoot();
        if (!context.settings().sandboxEnabled() || context.options().workspace() != WorkspaceMode.REPO
                || workspaceRoot == null) {
            return Optional.empty();
        }
        WorkspaceSandbox sandbox = null;
        try {
            sandbox = WorkspaceSandbox.create(workspaceRoot, context.settings().sandboxRoot(), context.runId());
            CompensationRequest compensation = new CompensationRequest(WorkspaceSandbox.DELETE_ACTION,
                    sandbox.path().toString(), "delete the sandbox of run " + context.runId());
            sandbox.apply(changeSet);
            return Optional.of(compensation);
        } catch (ChangeSetRejectedException e) {
            discard(sandbox, context);
            throw new StageFailure(FailureKind.INVALID_OUTPUT, "the workspace sandbox rejected the change set: "
                    + String.join("; ", e.problems()), e, null);
        } catch (IOException | IllegalArgumentException e) {
            discard(sandbox, context);
            throw new StageFailure(FailureKind.TRANSIENT, "could not materialize the sandbox: " + e.getMessage(), e,
                    null);
        }
    }

    private static void discard(@Nullable WorkspaceSandbox sandbox, StageContext context) {
        if (sandbox == null) {
            return;
        }
        try {
            WorkspaceSandbox.delete(context.settings().sandboxRoot(), sandbox.path());
        } catch (IOException | IllegalArgumentException e) {
            // best effort: a later sandbox of this run replaces the directory anyway
        }
    }
}
