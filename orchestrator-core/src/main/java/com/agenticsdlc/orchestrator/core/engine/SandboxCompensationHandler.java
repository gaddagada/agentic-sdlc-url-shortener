package com.agenticsdlc.orchestrator.core.engine;

import java.nio.file.Path;

import com.agenticsdlc.orchestrator.core.model.Compensation;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Executes {@code DELETE_SANDBOX} compensations: deletes the sandbox directory, refusing anything outside
 * the configured sandbox root.
 */
public final class SandboxCompensationHandler implements CompensationHandler {

    private final Path sandboxRoot;

    /**
     * Creates the handler.
     *
     * @param sandboxRoot the sandbox root directory
     */
    public SandboxCompensationHandler(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    @Override
    public String action() {
        return WorkspaceSandbox.DELETE_ACTION;
    }

    @Override
    public void compensate(Compensation compensation) throws Exception {
        WorkspaceSandbox.delete(sandboxRoot, Path.of(compensation.target()));
    }
}
