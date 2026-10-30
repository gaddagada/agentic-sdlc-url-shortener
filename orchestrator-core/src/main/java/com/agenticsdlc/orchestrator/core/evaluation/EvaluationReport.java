package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.agenticsdlc.orchestrator.core.artifact.Markdown;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * The evaluation of one run.
 *
 * @param runId the run id
 * @param stageScores score per stage id, in workflow order (stages without an artifact are absent)
 * @param overall mean of the stage scores (0 when no stage has an artifact)
 * @param notes notes about the evaluation (stages without an artifact, artifacts still under review)
 */
public record EvaluationReport(String runId, Map<String, Score> stageScores, double overall, List<String> notes) {

    /**
     * Copies the collections (keeping the stage order) and validates.
     */
    public EvaluationReport {
        Objects.requireNonNull(runId, "runId");
        stageScores = Immutables.map(stageScores);
        notes = Immutables.list(notes);
        if (!Double.isFinite(overall) || overall < 0.0 || overall > 1.0) {
            throw new IllegalArgumentException("overall must be within 0..1");
        }
    }

    /**
     * Renders the report as GitHub-flavoured Markdown: the overall score, a table of stage scores and the criteria of
     * every stage.
     *
     * @return Markdown text
     */
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Evaluation of run " + runId)
                .field("Overall score", percent(overall));
        List<List<String>> rows = new ArrayList<>();
        stageScores.forEach((stage, score) -> rows.add(List.of(stage, percent(score.value()),
                weakest(score))));
        md.heading(3, "Stages").table(List.of("Stage", "Score", "Weakest criterion"), rows);
        stageScores.forEach((stage, score) -> md.heading(3, stage + " (" + percent(score.value()) + ")")
                .table(List.of("Criterion", "Weight", "Value", "Rationale"), score.criteria().stream()
                        .map(c -> List.of(c.name(), c.applies() ? format(c.weight()) : "n/a", percent(c.value()),
                                c.rationale())).toList()));
        if (!notes.isEmpty()) {
            md.heading(3, "Notes").bullets(notes);
        }
        return md.render();
    }

    private static String weakest(Score score) {
        return score.criteria().stream().filter(Criterion::applies)
                .min((a, b) -> Double.compare(a.value(), b.value()))
                .map(c -> c.name() + " (" + percent(c.value()) + ")").orElse("-");
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private static String format(double weight) {
        return weight == Math.rint(weight) ? String.valueOf((long) weight) : String.valueOf(weight);
    }
}
