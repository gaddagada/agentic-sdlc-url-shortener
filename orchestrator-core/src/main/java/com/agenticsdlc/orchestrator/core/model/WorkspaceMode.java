package com.agenticsdlc.orchestrator.core.model;

/**
 * Whether the run reasons over the repository workspace.
 */
public enum WorkspaceMode {
    /** No codebase; greenfield structure is proposed. */
    NONE,
    /** The indexed repository workspace is analysed (brownfield). */
    REPO;
}
