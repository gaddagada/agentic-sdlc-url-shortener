package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code verify} stage. A report with {@code passed=false} is not a stage failure: the engine
 * feeds {@link #feedbackForImplementation()} back to the implementation stage (bounded feedback loop).
 *
 * @param structuralChecks structural checks over the change set, design and test plan
 * @param traceability requirement-to-test traceability
 * @param build optional real build outcome (only when a sandbox build ran)
 * @param passed {@code all checks passed && uncovered empty && (build == null || build.exitCode == 0)}
 * @param feedbackForImplementation imperative feedback for each failed check
 */
public record VerificationReport(List<Check> structuralChecks, Traceability traceability, @Nullable BuildOutcome build,
                                 boolean passed, List<String> feedbackForImplementation) implements ArtifactPayload {

    /**
     * Normalizes collections.
     */
    public VerificationReport {
        structuralChecks = Immutables.list(structuralChecks);
        feedbackForImplementation = Immutables.list(feedbackForImplementation);
    }

    /**
     * Requirement traceability.
     *
     * @param requirementsTotal number of functional requirements
     * @param requirementsCovered number traced by at least one test case
     * @param uncovered ids of uncovered requirements
     */
    public record Traceability(int requirementsTotal, int requirementsCovered, List<String> uncovered) {
        /**
         * Normalizes collections.
         */
        public Traceability {
            uncovered = Immutables.list(uncovered);
        }
    }

    /**
     * Outcome of a real build in the sandbox.
     *
     * @param executed whether the build ran
     * @param command the command
     * @param exitCode process exit code ({@code -1} on timeout)
     * @param durationMs duration
     * @param summary one-line summary (for example the surefire totals)
     * @param failures failing tests or error lines
     */
    public record BuildOutcome(boolean executed, String command, int exitCode, long durationMs, String summary,
                               List<String> failures) {
        /**
         * Normalizes fields.
         */
        public BuildOutcome {
            command = Immutables.text(command);
            summary = Immutables.text(summary);
            failures = Immutables.list(failures);
        }
    }

    /**
     * Computes the {@code passed} flag from its inputs.
     *
     * @param checks structural checks
     * @param traceability traceability
     * @param build optional build outcome
     * @return whether verification passed
     */
    public static boolean computePassed(List<Check> checks, Traceability traceability, @Nullable BuildOutcome build) {
        return checks.stream().allMatch(Check::passed) && traceability.uncovered().isEmpty()
                && (build == null || build.exitCode() == 0);
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.VERIFICATION_REPORT;
    }

    @Override
    public void validate() {
        Problems p = new Problems("VerificationReport");
        p.requireNonEmpty(structuralChecks, "structuralChecks");
        p.requireNonNull(traceability, "traceability");
        if (traceability != null) {
            p.require(traceability.requirementsCovered() >= 0
                            && traceability.requirementsCovered() <= traceability.requirementsTotal(),
                    "traceability covered must be within 0..total");
            p.require(traceability.requirementsTotal() - traceability.requirementsCovered()
                    == traceability.uncovered().size(), "traceability uncovered list must match total - covered");
            p.require(passed == computePassed(structuralChecks, traceability, build),
                    "passed flag is inconsistent with checks, traceability and build outcome");
        }
        p.require(passed || !feedbackForImplementation.isEmpty(),
                "a failed verification must carry feedback for implementation");
        for (Check c : structuralChecks) {
            p.requireText(c.name(), "check name");
        }
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Verification report")
                .field("Result", passed ? "PASSED" : "FAILED");
        md.heading(3, "Structural checks").table(List.of("Check", "Result", "Details"),
                structuralChecks.stream().map(c -> List.of(c.name(), c.passed() ? "pass" : "FAIL", c.details()))
                        .toList());
        if (traceability != null) {
            md.heading(3, "Traceability").field("Covered", traceability.requirementsCovered() + " / "
                    + traceability.requirementsTotal()).field("Uncovered", Markdown.join(traceability.uncovered()));
        }
        md.heading(3, "Build");
        if (build == null) {
            md.paragraph("No sandbox build was executed (structural verification only).");
        } else {
            md.field("Command", "`" + build.command() + "`").field("Exit code", build.exitCode())
                    .field("Duration", build.durationMs() + " ms").field("Summary", build.summary())
                    .bullets(build.failures());
        }
        md.heading(3, "Feedback for implementation").bullets(feedbackForImplementation);
        return md.render();
    }
}
