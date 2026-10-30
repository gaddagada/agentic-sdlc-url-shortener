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
 * @param passed {@code no blocking check failed && uncovered empty && (build == null || build.exitCode == 0)};
 *               failed advisory checks ({@link Check#blocking()} false) do not make it false
 * @param feedbackForImplementation imperative feedback for each failed blocking check (advisory findings may be
 *                                  listed too; they are routed back only together with a blocking failure)
 * @param skeletonOnly whether the verified change set contains SKELETON content, i.e. it is a change plan rather
 *                     than complete code: API checks only confirm that the endpoints are planned, and the release
 *                     can at best be GO_WITH_CONDITIONS; never null after construction (a boxed type so that JSON
 *                     written before the field existed reads as false instead of failing)
 * @param notes notes qualifying the verdict, for example {@value #NO_BUILD_NOTE} (empty when absent)
 */
public record VerificationReport(List<Check> structuralChecks, Traceability traceability, @Nullable BuildOutcome build,
                                 boolean passed, List<String> feedbackForImplementation, Boolean skeletonOnly,
                                 List<String> notes) implements ArtifactPayload {

    /** Note added when no sandbox build ran. */
    public static final String NO_BUILD_NOTE = "structural checks only; no build executed";

    /**
     * Normalizes collections; an absent {@code skeletonOnly} (older JSON) becomes false.
     */
    @SuppressWarnings("ConstantValue")
    public VerificationReport {
        structuralChecks = Immutables.list(structuralChecks);
        feedbackForImplementation = Immutables.list(feedbackForImplementation);
        skeletonOnly = skeletonOnly != null && skeletonOnly;
        notes = Immutables.list(notes);
    }

    /**
     * Creates a report of complete (non-skeleton) code without notes.
     *
     * @param structuralChecks structural checks
     * @param traceability traceability
     * @param build optional build outcome
     * @param passed whether verification passed
     * @param feedbackForImplementation feedback for each failed check
     */
    public VerificationReport(List<Check> structuralChecks, Traceability traceability, @Nullable BuildOutcome build,
                              boolean passed, List<String> feedbackForImplementation) {
        this(structuralChecks, traceability, build, passed, feedbackForImplementation, false, List.of());
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
     * Computes the {@code passed} flag from its inputs: no blocking check failed, no requirement is uncovered and the
     * build (if any) succeeded. Failed advisory checks do not fail a verification.
     *
     * @param checks structural checks
     * @param traceability traceability
     * @param build optional build outcome
     * @return whether verification passed
     */
    public static boolean computePassed(List<Check> checks, Traceability traceability, @Nullable BuildOutcome build) {
        return checks.stream().noneMatch(Check::failsBlocking) && traceability.uncovered().isEmpty()
                && (build == null || build.exitCode() == 0);
    }

    /**
     * Returns the failed advisory checks as {@code name: details} (empty when none); they are reported but do not
     * fail the verification.
     *
     * @return advisory findings in check order
     */
    public List<String> advisoryFindings() {
        return structuralChecks.stream().filter(c -> !c.passed() && !c.blocking())
                .map(c -> c.details().isBlank() ? c.name() : c.name() + ": " + c.details()).toList();
    }

    /**
     * Returns the names of the failed blocking checks (empty when none).
     *
     * @return blocking failures in check order
     */
    public List<String> blockingFailures() {
        return structuralChecks.stream().filter(Check::failsBlocking).map(Check::name).toList();
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
        if (skeletonOnly) {
            md.field("Scope", "skeleton change plan (structural checks; API changes planned, not implemented)");
        }
        if (!notes.isEmpty()) {
            md.heading(3, "Notes").bullets(notes);
        }
        md.heading(3, "Structural checks").table(List.of("Check", "Severity", "Result", "Details"),
                structuralChecks.stream().map(c -> List.of(c.name(), c.blocking() ? "blocking" : "advisory",
                        c.passed() ? "pass" : c.blocking() ? "FAIL" : "advisory finding", c.details())).toList());
        List<String> advisory = advisoryFindings();
        if (!advisory.isEmpty()) {
            md.heading(3, "Advisory findings (do not fail verification)").bullets(advisory);
        }
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
