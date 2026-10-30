package com.agenticsdlc.orchestrator.core.testsupport;

import java.time.Instant;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;

/**
 * Builds runs of the default SDLC graph with accepted artifacts, for rule and evaluator tests.
 */
public final class Runs {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Runs() {
    }

    public static Run create(String requirement, RunOptions options) {
        return Run.create("run-1", new WorkflowGraph(WorkflowDefinitions.defaultSdlc()), "test", requirement, options,
                T0);
    }

    /** Adds each payload as version 1 of its role's default stage and accepts it. */
    public static Run withAccepted(Run run, ArtifactPayload... payloads) {
        for (ArtifactPayload payload : payloads) {
            Artifact artifact = Contexts.artifact(payload);
            run.addArtifact(artifact);
            run.accept(artifact.stageId(), artifact.version());
        }
        return run;
    }
}
