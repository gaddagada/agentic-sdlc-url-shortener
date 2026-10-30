package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.ArrayList;
import java.util.List;

import com.agenticsdlc.orchestrator.core.evaluation.Criterion;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationReport;

/**
 * The evaluation of a run's artifacts (the core's {@code RunEvaluator}): a score within 0..1 per stage that has an
 * artifact (the accepted one, else the latest of the current plan), each the weighted mean of its criteria, and the
 * overall score (mean of the stage scores). Deterministic: the same run always gives the same report.
 *
 * @param runId run id
 * @param overall overall score within 0..1
 * @param stages stage scores in workflow order (stages without an artifact are absent)
 * @param notes notes about the evaluation (stages without an artifact, artifacts still under review)
 */
public record EvaluationReportView(String runId, double overall, List<StageScoreView> stages, List<String> notes) {

    /**
     * The score of one stage's artifact.
     *
     * @param stageId stage id
     * @param score score within 0..1
     * @param criteria every criterion, including those that do not apply to this run (weight 0)
     */
    public record StageScoreView(String stageId, double score, List<CriterionView> criteria) {
    }

    /**
     * One measured criterion.
     *
     * @param name stable criterion name
     * @param weight weight in the stage score (0 when it does not apply)
     * @param value measured value within 0..1 (1 is best)
     * @param applies whether it counts towards the score
     * @param rationale what was measured, with the numbers
     */
    public record CriterionView(String name, double weight, double value, boolean applies, String rationale) {
    }

    /**
     * Maps a core report.
     *
     * @param report the report
     * @return the view
     */
    public static EvaluationReportView from(EvaluationReport report) {
        List<StageScoreView> stages = new ArrayList<>();
        report.stageScores().forEach((stageId, score) -> stages.add(new StageScoreView(stageId, score.value(),
                score.criteria().stream().map(EvaluationReportView::criterion).toList())));
        return new EvaluationReportView(report.runId(), report.overall(), List.copyOf(stages), report.notes());
    }

    private static CriterionView criterion(Criterion c) {
        return new CriterionView(c.name(), c.weight(), c.value(), c.applies(), c.rationale());
    }
}
