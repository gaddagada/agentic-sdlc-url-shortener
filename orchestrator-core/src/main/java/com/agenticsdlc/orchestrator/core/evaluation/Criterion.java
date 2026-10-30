package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.Objects;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * One measured aspect of an artifact.
 *
 * @param name stable criterion name, for example {@code acceptance-criteria-per-fr}
 * @param weight weight in the artifact's score (0 when the criterion does not apply to this run, for example a
 *               codebase criterion of a greenfield run; such criteria are reported but do not count)
 * @param value measured value within 0..1 (1 is best)
 * @param rationale what was measured, with the numbers
 */
public record Criterion(String name, double weight, double value, String rationale) {

    /**
     * Validates the criterion and clamps the value to 0..1.
     *
     * @throws IllegalArgumentException for a blank name, a negative or non-finite weight or a non-finite value
     */
    public Criterion {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("criterion name must not be blank");
        }
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException("criterion weight must be a finite, non-negative number");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("criterion value must be finite");
        }
        value = Math.max(0.0, Math.min(1.0, value));
        rationale = Immutables.text(rationale);
    }

    /**
     * Returns whether the criterion counts towards the score.
     *
     * @return true when the weight is positive
     */
    public boolean applies() {
        return weight > 0.0;
    }
}
