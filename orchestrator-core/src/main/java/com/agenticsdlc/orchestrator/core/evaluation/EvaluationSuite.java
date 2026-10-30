package com.agenticsdlc.orchestrator.core.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.util.Immutables;

import tools.jackson.core.JacksonException;

/**
 * A suite of evaluation cases: requirements with the run options to use and the outcome the agents are expected to
 * reach. The core bundles a default suite ({@value #DEFAULT_RESOURCE}); {@link EvaluationSuiteRunner} executes a suite
 * through the engine.
 *
 * @param name suite name
 * @param version suite version
 * @param cases the cases (ids unique)
 */
public record EvaluationSuite(String name, String version, List<EvaluationCase> cases) {

    /** Classpath location of the default suite. */
    public static final String DEFAULT_RESOURCE = "/evaluation/default-suite.json";

    /**
     * Copies and validates the cases.
     *
     * @throws IllegalArgumentException for duplicate case ids
     */
    public EvaluationSuite {
        name = Immutables.text(name);
        version = Immutables.text(version);
        cases = Immutables.list(cases);
        Set<String> ids = new HashSet<>();
        for (EvaluationCase c : cases) {
            if (!ids.add(c.id())) {
                throw new IllegalArgumentException("duplicate evaluation case id " + c.id());
            }
        }
    }

    /**
     * One case of a suite.
     *
     * @param id case id (not blank)
     * @param description what the case demonstrates
     * @param requirement the requirement text (not blank)
     * @param scenario scenario key recorded on the run (default {@code eval:<id>})
     * @param workspace workspace mode (default NONE)
     * @param autonomy autonomy level (default GUIDED)
     * @param expectations the expected outcome
     */
    public record EvaluationCase(String id, String description, String requirement, String scenario,
                                 WorkspaceMode workspace, AutonomyLevel autonomy, Expectations expectations) {

        /**
         * Applies defaults and validates.
         *
         * @throws IllegalArgumentException for a blank id or requirement
         */
        @SuppressWarnings("ConstantValue")
        public EvaluationCase {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("evaluation case id must not be blank");
            }
            if (requirement == null || requirement.isBlank()) {
                throw new IllegalArgumentException("evaluation case " + id + " needs a requirement");
            }
            description = Immutables.text(description);
            scenario = scenario == null || scenario.isBlank() ? "eval:" + id : scenario;
            workspace = Objects.requireNonNullElse(workspace, WorkspaceMode.NONE);
            autonomy = Objects.requireNonNullElse(autonomy, AutonomyLevel.GUIDED);
            expectations = Objects.requireNonNullElse(expectations, Expectations.NONE);
        }
    }

    /**
     * The expected outcome of a case; null fields are not checked.
     *
     * @param expectClarification whether the requirements stage asks for clarification
     * @param expectedRiskAtLeast minimum risk level of the run at the end
     * @param expectPolicyDenial whether a policy denies the run
     * @param expectCompleted whether the run completes; unless it is false the runner drives the run (answers
     *                        clarifications with the first suggested answers and approves gates as its operator
     *                        identity); when false the runner stops at the first human gate
     * @param minOverallScore minimum overall evaluation score (0..1)
     * @param expectedReleaseDecision the release decision of the accepted release readiness
     */
    public record Expectations(@Nullable Boolean expectClarification, @Nullable RiskLevel expectedRiskAtLeast,
                               @Nullable Boolean expectPolicyDenial, @Nullable Boolean expectCompleted,
                               @Nullable Double minOverallScore,
                               ReleaseReadiness.@Nullable GoNoGo expectedReleaseDecision) {

        /** No expectations. */
        public static final Expectations NONE = new Expectations(null, null, null, null, null, null);

        /**
         * Validates the score bound.
         *
         * @throws IllegalArgumentException when {@code minOverallScore} is outside 0..1
         */
        public Expectations {
            if (minOverallScore != null && (minOverallScore < 0.0 || minOverallScore > 1.0)) {
                throw new IllegalArgumentException("minOverallScore must be within 0..1");
            }
        }

        /**
         * Returns whether the runner should drive the run (answer clarifications, approve gates).
         *
         * @return false only when {@code expectCompleted} is false
         */
        public boolean drivesToCompletion() {
            return !Boolean.FALSE.equals(expectCompleted);
        }
    }

    /**
     * Parses a suite from JSON.
     *
     * @param json the suite
     * @return the suite
     * @throws IllegalArgumentException when the JSON is malformed or a case is invalid
     */
    public static EvaluationSuite fromJson(String json) {
        try {
            EvaluationSuite suite = JsonCodec.standard().fromJson(json, EvaluationSuite.class);
            if (suite == null) {
                throw new IllegalArgumentException("evaluation suite is empty");
            }
            return suite;
        } catch (JacksonException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("invalid evaluation suite: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Loads the default suite bundled with the core ({@value #DEFAULT_RESOURCE}).
     *
     * @return the suite
     */
    public static EvaluationSuite loadDefault() {
        try (InputStream in = EvaluationSuite.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("evaluation suite not found: " + DEFAULT_RESOURCE));
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns a suite with the cases matching the given ids, in suite order.
     *
     * @param ids case ids
     * @return the filtered suite
     */
    public EvaluationSuite only(Set<String> ids) {
        List<EvaluationCase> selected = new ArrayList<>();
        for (EvaluationCase c : cases) {
            if (ids.contains(c.id())) {
                selected.add(c);
            }
        }
        return new EvaluationSuite(name, version, selected);
    }
}
