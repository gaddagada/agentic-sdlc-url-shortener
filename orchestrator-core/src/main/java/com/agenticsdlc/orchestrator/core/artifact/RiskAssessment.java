package com.agenticsdlc.orchestrator.core.artifact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A risk level with the factors that justify it.
 *
 * @param level the risk level
 * @param factors human-readable risk factors, for example {@code "public API change"}
 */
public record RiskAssessment(RiskLevel level, List<String> factors) {

    /**
     * Normalizes the factor list.
     */
    public RiskAssessment {
        level = Objects.requireNonNullElse(level, RiskLevel.LOW);
        factors = Immutables.list(factors);
    }

    /**
     * Returns a LOW assessment without factors.
     *
     * @return low risk
     */
    public static RiskAssessment low() {
        return new RiskAssessment(RiskLevel.LOW, List.of());
    }

    /**
     * Combines two assessments: the higher level and the union of factors (order preserved, no duplicates).
     *
     * @param other the other assessment
     * @return the combined assessment
     */
    public RiskAssessment merge(RiskAssessment other) {
        LinkedHashSet<String> union = new LinkedHashSet<>(factors);
        union.addAll(other.factors);
        return new RiskAssessment(RiskLevel.max(level, other.level), new ArrayList<>(union));
    }
}
