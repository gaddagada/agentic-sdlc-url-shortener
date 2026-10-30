package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Markdown;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * The result of running an {@link EvaluationSuite}.
 *
 * @param suite suite name
 * @param version suite version
 * @param cases the result of every case, in suite order
 */
public record SuiteResult(String suite, String version, List<CaseResult> cases) {

    /**
     * Copies the cases.
     */
    public SuiteResult {
        suite = Immutables.text(suite);
        version = Immutables.text(version);
        cases = Immutables.list(cases);
    }

    /**
     * The result of one case.
     *
     * @param caseId the case id
     * @param passed whether every expectation held
     * @param failures the expectations that did not hold (empty when passed)
     * @param observations what the runner observed and did (status, decisions, approvals given)
     * @param runId the run id, or null when the run could not be started
     * @param status the run status at the end, or null when the run could not be started
     * @param haltReason the halt reason, or null
     * @param report the evaluation of the run, or null when the run could not be started
     */
    public record CaseResult(String caseId, boolean passed, List<String> failures, List<String> observations,
                             @Nullable String runId, @Nullable RunStatus status, @Nullable HaltReason haltReason,
                             @Nullable EvaluationReport report) {
        /**
         * Copies the lists.
         */
        public CaseResult {
            Objects.requireNonNull(caseId, "caseId");
            failures = Immutables.list(failures);
            observations = Immutables.list(observations);
        }
    }

    /**
     * Returns whether every case passed.
     *
     * @return true when all cases passed (and there is at least one)
     */
    public boolean passed() {
        return !cases.isEmpty() && cases.stream().allMatch(CaseResult::passed);
    }

    /**
     * Returns the number of passed cases.
     *
     * @return passed cases
     */
    public long passedCount() {
        return cases.stream().filter(CaseResult::passed).count();
    }

    /**
     * Renders the result as Markdown: a summary table and the failures of failed cases.
     *
     * @return Markdown text
     */
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Evaluation suite " + suite + " " + version)
                .field("Result", passedCount() + "/" + cases.size() + " cases passed");
        md.table(List.of("Case", "Result", "Status", "Overall score", "Failures"), cases.stream()
                .map(c -> List.of(c.caseId(), c.passed() ? "pass" : "FAIL", String.valueOf(c.status()),
                        c.report() == null ? "-" : String.format(Locale.ROOT, "%.0f%%", c.report().overall() * 100),
                        c.failures().isEmpty() ? "-" : String.join("; ", c.failures()))).toList());
        return md.render();
    }
}
