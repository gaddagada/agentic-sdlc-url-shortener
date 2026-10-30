package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Scores the artifacts of a run with {@link ArtifactScorers}. Pure and deterministic: it reads a run snapshot (for
 * example from {@code OrchestrationEngine.get(runId)}) and never changes it; the same run always gives the same
 * report.
 *
 * <p>Per stage it scores the accepted artifact, or, when the stage has none yet (an exit gate awaiting approval),
 * the latest artifact of the stage in the current plan version, noting that it is still under review. The overall
 * score is the mean of the stage scores.</p>
 */
public final class RunEvaluator {

    private RunEvaluator() {
    }

    /**
     * Evaluates a run without a codebase index (codebase criteria do not apply).
     *
     * @param run the run (a snapshot copy)
     * @return the report
     */
    public static EvaluationReport evaluate(Run run) {
        return evaluate(run, null);
    }

    /**
     * Evaluates a run.
     *
     * @param run the run (a snapshot copy)
     * @param index the codebase index the run reasoned over (REPO runs), or null
     * @return the report
     */
    public static EvaluationReport evaluate(Run run, @Nullable CodebaseIndex index) {
        Map<String, Artifact> byStage = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (StageExecution stage : run.stages()) {
            Optional<Artifact> artifact = artifactOf(run, stage.stageId());
            if (artifact.isEmpty()) {
                missing.add(stage.stageId());
                continue;
            }
            byStage.put(stage.stageId(), artifact.get());
            if (run.acceptedArtifact(stage.stageId()).isEmpty()) {
                notes.add(stage.stageId() + ": scored " + artifact.get().reference() + ", which is not accepted yet");
            }
        }
        Map<ArtifactKind, ArtifactPayload> payloads = new EnumMap<>(ArtifactKind.class);
        byStage.values().forEach(a -> payloads.putIfAbsent(a.kind(), a.payload()));
        ScoringContext context = new ScoringContext(payloads, index, run.options().workspace(),
                run.options().autonomy());
        Map<String, Score> scores = new LinkedHashMap<>();
        byStage.forEach((stageId, artifact) -> scores.put(stageId,
                ArtifactScorers.forKind(artifact.kind()).scoreAny(artifact.payload(), context)));
        if (!missing.isEmpty()) {
            notes.add("no artifact to evaluate for: " + String.join(", ", missing));
        }
        double overall = scores.values().stream().mapToDouble(Score::value).average().orElse(0.0);
        return new EvaluationReport(run.id(), scores, overall, notes);
    }

    private static Optional<Artifact> artifactOf(Run run, String stageId) {
        Optional<Artifact> accepted = run.acceptedArtifact(stageId);
        if (accepted.isPresent()) {
            return accepted;
        }
        return run.artifacts().stream()
                .filter(a -> a.stageId().equals(stageId) && a.planVersion() == run.planVersion())
                .max(Comparator.comparingInt(Artifact::version));
    }
}
