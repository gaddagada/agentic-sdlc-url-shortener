package com.agenticsdlc.orchestrator.core.policy.pack;

import java.util.List;
import java.util.Objects;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.util.Immutables;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The condition of a declarative rule: a small closed sum type, written in JSON as an object whose {@code type}
 * property names the variant (for example {@code "type": "pathMatches"} with a {@code globs} array of strings).
 * Conditions are evaluated against the
 * {@link com.agenticsdlc.orchestrator.core.policy.PolicyContext} of a gate:
 *
 * <ul>
 *   <li>{@code intentMatches} ({@link IntentMatches}): a regex is found (case-insensitively) in the requirement under
 *       evaluation, the clarification answers of the run, or (at the exit of the requirements stage) the normalized
 *       statement of the candidate specification;</li>
 *   <li>{@code pathMatches} ({@link PathMatches}): a glob (the syntax of the built-in protected-path rule:
 *       {@code **}{@code /} any directories, {@code *} within one segment, case-insensitive) matches a path of the
 *       candidate change set, or of the candidate impact analysis (components that are not mere regression risks), or,
 *       without such a candidate, of the run's accepted change set and impact analysis;</li>
 *   <li>{@code payloadMatches} ({@link PayloadMatches}): a regex is found (case-sensitively; use {@code (?i)} for
 *       case-insensitive matching) in the strict canonical JSON of the candidate payload (EXIT gates only);</li>
 *   <li>{@code riskAtLeast} ({@link RiskAtLeast}): the run's current risk (for exit gates: the risk once the
 *       candidate is accepted) is at least the level;</li>
 *   <li>{@code changeSizeOver} ({@link ChangeSizeOver}): the candidate (else the accepted) change set has more files
 *       or more content lines than the limits;</li>
 *   <li>{@code releaseDecisionIs} ({@link ReleaseDecisionIs}): the candidate (else the accepted) release readiness has
 *       the decision;</li>
 *   <li>{@code autonomyIs} ({@link AutonomyIs}): the run's autonomy level is the level;</li>
 *   <li>{@code allOf}, {@code anyOf}, {@code not}: combinators.</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = Condition.TYPE_PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Condition.IntentMatches.class, name = Condition.INTENT_MATCHES),
        @JsonSubTypes.Type(value = Condition.PathMatches.class, name = Condition.PATH_MATCHES),
        @JsonSubTypes.Type(value = Condition.PayloadMatches.class, name = Condition.PAYLOAD_MATCHES),
        @JsonSubTypes.Type(value = Condition.RiskAtLeast.class, name = Condition.RISK_AT_LEAST),
        @JsonSubTypes.Type(value = Condition.ChangeSizeOver.class, name = Condition.CHANGE_SIZE_OVER),
        @JsonSubTypes.Type(value = Condition.ReleaseDecisionIs.class, name = Condition.RELEASE_DECISION_IS),
        @JsonSubTypes.Type(value = Condition.AutonomyIs.class, name = Condition.AUTONOMY_IS),
        @JsonSubTypes.Type(value = Condition.AllOf.class, name = Condition.ALL_OF),
        @JsonSubTypes.Type(value = Condition.AnyOf.class, name = Condition.ANY_OF),
        @JsonSubTypes.Type(value = Condition.Not.class, name = Condition.NOT)})
public sealed interface Condition {

    /** Name of the JSON discriminator property. */
    String TYPE_PROPERTY = "type";
    /** Type name of {@link IntentMatches}. */
    String INTENT_MATCHES = "intentMatches";
    /** Type name of {@link PathMatches}. */
    String PATH_MATCHES = "pathMatches";
    /** Type name of {@link PayloadMatches}. */
    String PAYLOAD_MATCHES = "payloadMatches";
    /** Type name of {@link RiskAtLeast}. */
    String RISK_AT_LEAST = "riskAtLeast";
    /** Type name of {@link ChangeSizeOver}. */
    String CHANGE_SIZE_OVER = "changeSizeOver";
    /** Type name of {@link ReleaseDecisionIs}. */
    String RELEASE_DECISION_IS = "releaseDecisionIs";
    /** Type name of {@link AutonomyIs}. */
    String AUTONOMY_IS = "autonomyIs";
    /** Type name of {@link AllOf}. */
    String ALL_OF = "allOf";
    /** Type name of {@link AnyOf}. */
    String ANY_OF = "anyOf";
    /** Type name of {@link Not}. */
    String NOT = "not";

    /**
     * Matches the requirement and clarification text.
     *
     * @param regexes Java regular expressions, found case-insensitively; any match satisfies the condition
     */
    record IntentMatches(List<String> regexes) implements Condition {
        /**
         * Copies the list.
         */
        public IntentMatches {
            regexes = Immutables.list(regexes);
        }
    }

    /**
     * Matches change-set and impact paths.
     *
     * @param globs repository path globs; any match satisfies the condition
     */
    record PathMatches(List<String> globs) implements Condition {
        /**
         * Copies the list.
         */
        public PathMatches {
            globs = Immutables.list(globs);
        }
    }

    /**
     * Matches the candidate payload's strict canonical JSON (EXIT gates).
     *
     * @param regexes Java regular expressions, found case-sensitively; any match satisfies the condition
     */
    record PayloadMatches(List<String> regexes) implements Condition {
        /**
         * Copies the list.
         */
        public PayloadMatches {
            regexes = Immutables.list(regexes);
        }
    }

    /**
     * Matches when the run's current risk is at least a level.
     *
     * @param level the minimum risk level
     */
    record RiskAtLeast(RiskLevel level) implements Condition {
        /**
         * Validates the level.
         */
        public RiskAtLeast {
            Objects.requireNonNull(level, "level");
        }
    }

    /**
     * Matches a change set with more than {@code files} files or more than {@code lines} content lines.
     *
     * @param files file limit (not negative)
     * @param lines content line limit (not negative)
     */
    record ChangeSizeOver(int files, int lines) implements Condition {
    }

    /**
     * Matches a release readiness decision.
     *
     * @param decision the decision
     */
    record ReleaseDecisionIs(ReleaseReadiness.GoNoGo decision) implements Condition {
        /**
         * Validates the decision.
         */
        public ReleaseDecisionIs {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * Matches the run's autonomy level.
     *
     * @param autonomy the autonomy level
     */
    record AutonomyIs(AutonomyLevel autonomy) implements Condition {
        /**
         * Validates the level.
         */
        public AutonomyIs {
            Objects.requireNonNull(autonomy, "autonomy");
        }
    }

    /**
     * Matches when every condition matches.
     *
     * @param conditions the conditions (not empty)
     */
    record AllOf(List<Condition> conditions) implements Condition {
        /**
         * Copies the list.
         */
        public AllOf {
            conditions = Immutables.list(conditions);
        }
    }

    /**
     * Matches when any condition matches.
     *
     * @param conditions the conditions (not empty)
     */
    record AnyOf(List<Condition> conditions) implements Condition {
        /**
         * Copies the list.
         */
        public AnyOf {
            conditions = Immutables.list(conditions);
        }
    }

    /**
     * Matches when the condition does not match.
     *
     * @param condition the negated condition
     */
    record Not(Condition condition) implements Condition {
        /**
         * Validates the condition.
         */
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }
}
