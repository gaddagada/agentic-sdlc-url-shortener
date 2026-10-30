package com.agenticsdlc.orchestrator.app.startup;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.config.WorkspaceIndexHolder;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Health of the codebase index, exposed as {@code workspaceIndex} in {@code /actuator/health}: UP when the index
 * contains types or workspace indexing is disabled (every run behaves like workspace NONE), UNKNOWN before the
 * first indexing, DOWN when the workspace yielded no types (brownfield reasoning would be blind).
 */
@Component("workspaceIndexHealthIndicator")
public class WorkspaceIndexHealthIndicator implements HealthIndicator {

    private final WorkspaceIndexHolder workspace;

    /**
     * Creates the indicator.
     *
     * @param workspace workspace index holder
     */
    public WorkspaceIndexHealthIndicator(WorkspaceIndexHolder workspace) {
        this.workspace = workspace;
    }

    @Override
    public Health health() {
        String root = workspace.root().toString();
        if (!workspace.enabled()) {
            return Health.up().withDetail("mode", "NONE (workspace indexing disabled)").build();
        }
        CodebaseIndex index = workspace.current();
        if (index == null) {
            return Health.unknown().withDetail("root", root).withDetail("state", "not indexed yet").build();
        }
        if (index.types().isEmpty()) {
            String error = workspace.lastError();
            return Health.down().withDetail("root", root)
                    .withDetail("error", error == null ? "no Java types found under the workspace root" : error)
                    .build();
        }
        return Health.up().withDetail("root", root).withDetail("types", index.types().size())
                .withDetail("endpoints", index.endpoints().size()).withDetail("migrations", index.migrations().size())
                .withDetail("indexedAt", index.indexedAt().toString()).build();
    }
}
