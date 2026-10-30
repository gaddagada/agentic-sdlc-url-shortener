package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * The score of one artifact: the weighted mean of its criteria.
 *
 * @param value score within 0..1 ({@link #of(List)}: sum of weight times value divided by the sum of weights; 0 when
 *              no criterion applies)
 * @param criteria every criterion, including those with weight 0 (not applicable)
 */
public record Score(double value, List<Criterion> criteria) {

    /**
     * Copies the criteria and validates the value.
     */
    public Score {
        criteria = Immutables.list(criteria);
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("score must be within 0..1");
        }
    }

    /**
     * Computes the weighted mean of criteria.
     *
     * @param criteria the criteria
     * @return the score
     */
    public static Score of(List<Criterion> criteria) {
        double weights = 0.0;
        double sum = 0.0;
        for (Criterion criterion : criteria) {
            weights += criterion.weight();
            sum += criterion.weight() * criterion.value();
        }
        double value = weights == 0.0 ? 0.0 : sum / weights;
        return new Score(Math.max(0.0, Math.min(1.0, value)), criteria);
    }

    /**
     * Returns a criterion by name.
     *
     * @param name criterion name
     * @return the criterion, or empty
     */
    public Optional<Criterion> criterion(String name) {
        return criteria.stream().filter(c -> c.name().equals(name)).findFirst();
    }
}
