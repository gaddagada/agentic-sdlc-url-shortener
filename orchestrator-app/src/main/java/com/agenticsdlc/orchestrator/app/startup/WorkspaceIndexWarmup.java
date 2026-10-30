package com.agenticsdlc.orchestrator.app.startup;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.config.WorkspaceIndexHolder;

/**
 * Indexes the repository workspace once at startup so that the first brownfield run (and
 * {@code GET /api/v1/workspace/index}) sees the codebase; {@code POST /api/v1/workspace/reindex} refreshes it.
 */
@Component
@Order(WorkspaceIndexWarmup.ORDER)
public class WorkspaceIndexWarmup implements ApplicationRunner {

    /** Runner order. */
    static final int ORDER = 10;

    private final WorkspaceIndexHolder workspace;

    /**
     * Creates the runner.
     *
     * @param workspace workspace index holder
     */
    public WorkspaceIndexWarmup(WorkspaceIndexHolder workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run(ApplicationArguments args) {
        workspace.reindex();
    }
}
