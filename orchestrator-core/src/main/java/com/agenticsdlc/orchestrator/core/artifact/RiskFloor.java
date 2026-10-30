package com.agenticsdlc.orchestrator.core.artifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Deterministic risk floor computed by the engine from artifact facts, so that an agent cannot lower the
 * run's risk (and thereby skip risk-based approvals) by under-reporting {@code DesignSpec.risk} or
 * {@code RequirementSpec.preliminaryRisk}. The run's current risk is the higher of the agent-reported risk
 * and this floor.
 *
 * <ul>
 *   <li>HIGH: a data model change that is not backward compatible, a breaking API change, personal data
 *   fields in the design's data handling, or an impacted component of type FILTER or CONFIG or whose path
 *   contains {@code /security/}.</li>
 *   <li>MEDIUM: any data model or API change, or an impacted component whose nature is MODIFY or EXTEND.</li>
 *   <li>LOW otherwise.</li>
 * </ul>
 *
 * <p>Every factor is prefixed with {@value #FACTOR_PREFIX} so the audit trail distinguishes engine facts from
 * agent claims.</p>
 */
public final class RiskFloor {

    /** Prefix of every factor produced by the floor. */
    public static final String FACTOR_PREFIX = "engine: ";

    private RiskFloor() {
    }

    /**
     * Computes the floor from a design and an impact analysis (either may be absent).
     *
     * @param design the design, or null
     * @param impact the impact analysis, or null
     * @return the floor; LOW without factors when nothing raises it
     */
    public static RiskAssessment assess(@Nullable DesignSpec design, @Nullable ImpactAnalysis impact) {
        List<String> high = new ArrayList<>();
        List<String> medium = new ArrayList<>();
        if (design != null) {
            for (DesignSpec.DataModelChange change : design.dataModelChanges()) {
                if (!change.backwardCompatible()) {
                    high.add("non-backward-compatible data model change (" + change.entity() + ")");
                }
            }
            for (DesignSpec.ApiChange change : design.apiChanges()) {
                if (change.breaking()) {
                    high.add("breaking API change (" + change.method() + " " + change.path() + ")");
                }
            }
            if (design.dataHandling() != null && !design.dataHandling().piiFields().isEmpty()) {
                high.add("personal data processed (" + String.join(", ", design.dataHandling().piiFields()) + ")");
            }
            if (!design.dataModelChanges().isEmpty()) {
                medium.add("data model change (" + design.dataModelChanges().size() + ")");
            }
            if (!design.apiChanges().isEmpty()) {
                medium.add("API change (" + design.apiChanges().size() + ")");
            }
        }
        if (impact != null) {
            for (ImpactAnalysis.ImpactedComponent component : impact.components()) {
                String path = component.path().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (component.type() == ImpactAnalysis.ComponentType.FILTER
                        || component.type() == ImpactAnalysis.ComponentType.CONFIG) {
                    high.add("impacted " + component.type() + " component (" + component.name() + ")");
                } else if (path.contains("/security/")) {
                    high.add("impacted security component (" + component.name() + ")");
                }
                if (component.nature() == ImpactAnalysis.ChangeNature.MODIFY
                        || component.nature() == ImpactAnalysis.ChangeNature.EXTEND) {
                    medium.add("existing component changed (" + component.name() + ")");
                }
            }
        }
        if (!high.isEmpty()) {
            return new RiskAssessment(RiskLevel.HIGH, prefixed(high));
        }
        if (!medium.isEmpty()) {
            return new RiskAssessment(RiskLevel.MEDIUM, prefixed(medium));
        }
        return RiskAssessment.low();
    }

    private static List<String> prefixed(List<String> reasons) {
        return reasons.stream().distinct().map(r -> FACTOR_PREFIX + r).toList();
    }
}
