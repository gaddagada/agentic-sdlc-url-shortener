package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.evaluation.SuiteResult;

/**
 * The result of an evaluation suite run.
 *
 * @param suite suite name
 * @param version suite version
 * @param provider agent provider the suite ran with
 * @param passed whether every case passed
 * @param passedCount cases that passed
 * @param total cases
 * @param cases the result of every case, in suite order
 * @param markdown the result as a Markdown summary table
 */
public record SuiteResultView(String suite, String version, String provider, boolean passed, long passedCount,
                              int total, List<CaseView> cases, String markdown) {

    /**
     * The result of one case.
     *
     * @param caseId case id
     * @param passed whether every expectation held
     * @param failures the expectations that did not hold
     * @param observations what the runner observed and did (decisions, approvals it gave as evaluation-runner)
     * @param runId id of the case's run in the isolated evaluation engine (not stored by the control plane), or null
     * @param status final run status, or null
     * @param haltReason halt reason, or null
     * @param report evaluation of the run, or null when it could not be started
     */
    public record CaseView(String caseId, boolean passed, List<String> failures, List<String> observations,
                           @Nullable String runId, @Nullable String status, @Nullable String haltReason,
                           @Nullable EvaluationReportView report) {
    }

    /**
     * Maps a core suite result.
     *
     * @param result the result
     * @param provider the provider the suite ran with
     * @return the view
     */
    public static SuiteResultView from(SuiteResult result, String provider) {
        List<CaseView> cases = result.cases().stream().map(c -> new CaseView(c.caseId(), c.passed(), c.failures(),
                c.observations(), c.runId(), c.status() == null ? null : c.status().name(),
                c.haltReason() == null ? null : c.haltReason().name(),
                c.report() == null ? null : EvaluationReportView.from(c.report()))).toList();
        return new SuiteResultView(result.suite(), result.version(), provider, result.passed(), result.passedCount(),
                result.cases().size(), cases, result.markdown());
    }
}
