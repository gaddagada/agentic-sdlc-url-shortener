package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.util.Immutables;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * What a scorer may consult besides the artifact it scores: the run's other artifacts (for cross-references such as
 * requirement coverage), the codebase index of REPO runs, the workspace mode and the autonomy level.
 *
 * @param artifacts the run's artifacts by kind (the ones the evaluator scores)
 * @param index the codebase index, or null (codebase criteria then do not apply)
 * @param workspace the run's workspace mode
 * @param autonomy the run's autonomy level
 */
public record ScoringContext(Map<ArtifactKind, ArtifactPayload> artifacts, @Nullable CodebaseIndex index,
                             WorkspaceMode workspace, AutonomyLevel autonomy) {

    /**
     * Copies the map and validates.
     */
    public ScoringContext {
        artifacts = Immutables.map(artifacts);
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(autonomy, "autonomy");
    }

    /**
     * Returns a context without other artifacts and without an index (GUIDED, no workspace).
     *
     * @return the empty context
     */
    public static ScoringContext empty() {
        return new ScoringContext(Map.of(), null, WorkspaceMode.NONE, AutonomyLevel.GUIDED);
    }

    /**
     * Returns the artifact payload of the given type.
     *
     * @param type payload type
     * @param <T> payload type
     * @return the payload, or empty
     */
    public <T extends ArtifactPayload> Optional<T> find(Class<T> type) {
        return artifacts.values().stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /**
     * Returns whether a non-empty codebase index of a REPO run is available.
     *
     * @return true when codebase criteria apply
     */
    public boolean hasCodebase() {
        return workspace == WorkspaceMode.REPO && index != null && !index.isEmpty();
    }
}
