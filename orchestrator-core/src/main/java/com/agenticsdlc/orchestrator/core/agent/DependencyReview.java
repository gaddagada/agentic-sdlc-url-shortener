package com.agenticsdlc.orchestrator.core.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.BuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.DependencyFlag;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.FlagKind;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Adds the dependency inventory and the optional external dependency scan to a {@link SecurityReview}. Shared by the
 * deterministic and the LLM security review agents, so the inventory in a review is always read from the build files
 * by the engine, never taken from model output.
 *
 * <p>The inventory ({@link DependencyInventory}) is an inventory and hygiene check of the Maven build files, not a
 * vulnerability scan. It runs for REPO runs over the run's sandbox when one exists (it then includes the change), else
 * over the workspace root. It adds the check {@value #INVENTORY_CHECK} (passed when no flag is HIGH) and one finding
 * per kind of flag (titles start with {@value #FINDING_PREFIX}).</p>
 *
 * <p>The external scan ({@link AgentSettings#dependencyScanCommand()}, for example an OWASP dependency-check goal that
 * fails on known CVEs) runs only in the run's sandbox, through the same {@link BuildRunner} and allow-list as sandbox
 * builds. Only its exit status and the first {@value #MAX_SCAN_OUTPUT_CHARS} characters of its captured output (the
 * runner keeps the last lines of the output; credential-like tokens are redacted) are recorded, in the check
 * {@value #SCAN_CHECK}. A non-zero exit is a HIGH finding; a scan that could not run or timed out is a MEDIUM
 * finding; a configured scan without a sandbox or without a runner is reported in an advisory check only.</p>
 */
public final class DependencyReview {

    /** Name of the inventory check. */
    public static final String INVENTORY_CHECK = "dependency-inventory";

    /** Name of the external scan check. */
    public static final String SCAN_CHECK = "dependency-scan";

    /** Title prefix of inventory findings. */
    public static final String FINDING_PREFIX = "Dependency hygiene: ";

    /** Title prefix of external scan findings. */
    public static final String SCAN_FINDING_PREFIX = "Dependency scan: ";

    /** Maximum characters of scan output recorded. */
    public static final int MAX_SCAN_OUTPUT_CHARS = 2000;

    /** Coordinates listed per finding before "and n more". */
    static final int MAX_LISTED = 10;

    private DependencyReview() {
    }

    /** How the external scan ended. */
    public enum ScanStatus {
        /** Not attempted: the run has no sandbox, or no runner is available (reported in an advisory check). */
        SKIPPED,
        /** The runner refused the command (not on the allow-list) or could not start it. */
        NOT_EXECUTED,
        /** The scan was stopped by its timeout. */
        TIMED_OUT,
        /** The scan ran to completion (its exit code tells the result). */
        COMPLETED
    }

    /**
     * Outcome of the external dependency scan.
     *
     * @param command the command
     * @param status how the scan ended
     * @param exitCode exit code ({@code -1} unless {@link ScanStatus#COMPLETED})
     * @param output the first {@value #MAX_SCAN_OUTPUT_CHARS} characters of the captured output, credential-like
     *               tokens redacted (the reason or the runner's summary when there was no output)
     */
    public record ScanOutcome(String command, ScanStatus status, int exitCode, String output) {

        /**
         * Returns whether the scan completed with exit code 0.
         *
         * @return true for a clean scan
         */
        public boolean passed() {
            return status == ScanStatus.COMPLETED && exitCode == 0;
        }
    }

    /**
     * Returns the directory whose build files the inventory reads: for REPO runs the run's sandbox when it exists,
     * else the configured workspace root; empty for other runs or without a workspace root.
     *
     * @param context the stage context
     * @return the directory, or empty
     */
    public static Optional<Path> inventoryRoot(StageContext context) {
        if (context.options().workspace() != WorkspaceMode.REPO) {
            return Optional.empty();
        }
        Optional<Path> sandbox = sandbox(context);
        return sandbox.isPresent() ? sandbox : Optional.ofNullable(context.settings().workspaceRoot());
    }

    private static Optional<Path> sandbox(StageContext context) {
        Path sandbox = WorkspaceSandbox.pathFor(context.settings().sandboxRoot(), context.runId());
        return Files.isDirectory(sandbox) ? Optional.of(sandbox) : Optional.empty();
    }

    /**
     * Reads the inventory of the context's workspace.
     *
     * @param context the stage context
     * @return the inventory, or empty when the run has no workspace to inventory
     */
    public static Optional<DependencyInventory> inventory(StageContext context) {
        return inventoryRoot(context).map(DependencyInventory::read);
    }

    /**
     * Runs the configured external dependency scan in the run's sandbox.
     *
     * @param context the stage context
     * @param runner the runner, or null when none is available
     * @return the outcome, or null when no scan command is configured
     * @throws StageFailure TRANSIENT when interrupted while waiting for the scan
     */
    public static @Nullable ScanOutcome scan(StageContext context, @Nullable BuildRunner runner) throws StageFailure {
        String command = context.settings().dependencyScanCommand();
        if (command == null || context.options().workspace() != WorkspaceMode.REPO) {
            return null;
        }
        Optional<Path> sandbox = sandbox(context);
        if (runner == null || sandbox.isEmpty()) {
            return new ScanOutcome(command, ScanStatus.SKIPPED, -1, runner == null ? "no build runner is configured"
                    : "the run has no sandbox (enable sandboxes for REPO runs)");
        }
        try {
            BuildResult result = runner.run(sandbox.get(), command, context.settings().dependencyScanTimeout());
            String output = result.outputTail().isEmpty() ? result.summary() : String.join("\n", result.outputTail());
            ScanStatus status = !result.executed() ? ScanStatus.NOT_EXECUTED
                    : result.exitCode() == -1 ? ScanStatus.TIMED_OUT : ScanStatus.COMPLETED;
            return new ScanOutcome(command, status, status == ScanStatus.COMPLETED ? result.exitCode() : -1,
                    bounded(output));
        } catch (IllegalArgumentException e) {
            return new ScanOutcome(command, ScanStatus.NOT_EXECUTED, -1, bounded("not allowed: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw StageFailure.transientFailure("dependency scan interrupted");
        }
    }

    /**
     * Reads the inventory, runs the configured scan and applies both to a review ({@link #apply}).
     *
     * @param review the review
     * @param context the stage context
     * @param runner runner for the scan, or null
     * @return the completed review
     * @throws StageFailure TRANSIENT when interrupted while waiting for the scan
     */
    public static SecurityReview complete(SecurityReview review, StageContext context, @Nullable BuildRunner runner)
            throws StageFailure {
        return apply(review, inventory(context).orElse(null), scan(context, runner));
    }

    /**
     * Applies an inventory and a scan outcome to a review: the review's dependencies are replaced by the inventory's
     * (empty without an inventory), any {@value #INVENTORY_CHECK} and {@value #SCAN_CHECK} checks and any findings
     * with the inventory or scan title prefixes are replaced by the ones computed here, and {@code passed} is
     * recomputed from the findings.
     *
     * @param review the review
     * @param inventory the inventory, or null when the run has no workspace
     * @param scan the scan outcome, or null when no scan is configured
     * @return the new review
     */
    public static SecurityReview apply(SecurityReview review, @Nullable DependencyInventory inventory,
                                       @Nullable ScanOutcome scan) {
        List<Check> checks = new ArrayList<>(review.checks().stream()
                .filter(c -> !c.name().equals(INVENTORY_CHECK) && !c.name().equals(SCAN_CHECK)).toList());
        List<SecurityReview.Finding> findings = new ArrayList<>(review.findings().stream()
                .filter(f -> !f.title().startsWith(FINDING_PREFIX) && !f.title().startsWith(SCAN_FINDING_PREFIX))
                .toList());
        if (inventory != null) {
            checks.add(inventoryCheck(inventory));
            findings.addAll(inventoryFindings(inventory));
        }
        if (scan != null) {
            applyScan(scan, checks, findings);
        }
        return new SecurityReview(review.threats(), checks, findings, review.complianceNotes(),
                SecurityReview.computePassed(findings), inventory == null ? List.of() : inventory.dependencies());
    }

    private static Check inventoryCheck(DependencyInventory inventory) {
        List<DependencyFlag> high = inventory.flagsAtLeast(Severity.HIGH);
        Map<Severity, Long> bySeverity = inventory.flags().stream()
                .collect(Collectors.groupingBy(DependencyFlag::severity, LinkedHashMap::new, Collectors.counting()));
        String counts = bySeverity.isEmpty() ? "no hygiene flags" : "flags: " + bySeverity.entrySet().stream()
                .sorted(Map.Entry.<Severity, Long>comparingByKey(Comparator.reverseOrder()))
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        String details = inventory.modules().size() + " POMs, " + inventory.dependencies().size()
                + " declarations; " + counts + " (inventory and hygiene check, not a CVE scan)"
                + (inventory.notes().isEmpty() ? "" : "; " + String.join("; ", inventory.notes()));
        return high.isEmpty() ? Check.pass(INVENTORY_CHECK, details) : Check.fail(INVENTORY_CHECK, details);
    }

    private static List<SecurityReview.Finding> inventoryFindings(DependencyInventory inventory) {
        Map<FlagKind, List<DependencyFlag>> byKind = new LinkedHashMap<>();
        for (DependencyFlag flag : inventory.flags()) {
            byKind.computeIfAbsent(flag.kind(), k -> new ArrayList<>()).add(flag);
        }
        List<SecurityReview.Finding> findings = new ArrayList<>();
        for (FlagKind kind : FlagKind.values()) {
            List<DependencyFlag> flags = byKind.get(kind);
            if (flags == null) {
                continue;
            }
            Severity severity = flags.stream().map(DependencyFlag::severity).max(Comparator.naturalOrder())
                    .orElse(Severity.LOW);
            String listed = flags.stream().limit(MAX_LISTED).map(f -> (f.coordinate().isEmpty() ? f.module()
                    : f.coordinate() + " in " + f.module())).collect(Collectors.joining("; "))
                    + (flags.size() > MAX_LISTED ? "; and " + (flags.size() - MAX_LISTED) + " more" : "");
            findings.add(new SecurityReview.Finding(severity, FINDING_PREFIX + title(kind) + " (" + flags.size() + ")",
                    recommendation(kind) + ": " + listed));
        }
        return findings;
    }

    private static String title(FlagKind kind) {
        return switch (kind) {
            case SYSTEM_SCOPE -> "system-scoped dependencies";
            case DYNAMIC_VERSION -> "LATEST or RELEASE versions";
            case VERSION_RANGE -> "version ranges";
            case SNAPSHOT_VERSION -> "SNAPSHOT versions of external artifacts";
            case DUPLICATE_DECLARATION -> "duplicate declarations";
            case UNVERSIONED_PLUGIN -> "plugins without a managed version";
            case UNPARSEABLE_POM -> "POMs that cannot be parsed securely";
        };
    }

    private static String recommendation(FlagKind kind) {
        return switch (kind) {
            case SYSTEM_SCOPE -> "Publish the artifact to a repository and use a normal scope";
            case DYNAMIC_VERSION -> "Pin an explicit, reviewed version";
            case VERSION_RANGE -> "Pin an explicit version instead of a range";
            case SNAPSHOT_VERSION -> "Depend on released versions";
            case DUPLICATE_DECLARATION -> "Remove the duplicate declaration";
            case UNVERSIONED_PLUGIN -> "Declare the plugin version or manage it in pluginManagement";
            case UNPARSEABLE_POM -> "Fix the POM (DOCTYPE declarations and entities are not allowed)";
        };
    }

    private static void applyScan(ScanOutcome scan, List<Check> checks, List<SecurityReview.Finding> findings) {
        String outcome = switch (scan.status()) {
            case SKIPPED -> "not executed";
            case NOT_EXECUTED -> "could not run";
            case TIMED_OUT -> "timed out";
            case COMPLETED -> "exit code " + scan.exitCode();
        };
        String details = "command `" + scan.command() + "`; " + outcome + "; output: " + scan.output();
        if (scan.status() == ScanStatus.SKIPPED) {
            checks.add(Check.advisory(SCAN_CHECK, false, details));
            return;
        }
        checks.add(scan.passed() ? Check.pass(SCAN_CHECK, details) : Check.fail(SCAN_CHECK, details));
        if (scan.passed()) {
            return;
        }
        if (scan.status() == ScanStatus.COMPLETED) {
            findings.add(new SecurityReview.Finding(Severity.HIGH, SCAN_FINDING_PREFIX
                    + "the external scanner reported problems (exit code " + scan.exitCode() + ")",
                    "Review the scanner report and upgrade or replace the affected dependencies"));
        } else {
            findings.add(new SecurityReview.Finding(Severity.MEDIUM, SCAN_FINDING_PREFIX + "the external scanner did "
                    + "not complete (" + outcome + ")", "Fix the scan command or its environment and re-run the "
                    + "review"));
        }
    }

    /** Redacts credential-like tokens and keeps the first {@value #MAX_SCAN_OUTPUT_CHARS} characters. */
    static String bounded(String output) {
        String redacted = output;
        for (Pattern pattern : SecretPatterns.PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[redacted]");
        }
        return redacted.length() <= MAX_SCAN_OUTPUT_CHARS ? redacted : redacted.substring(0, MAX_SCAN_OUTPUT_CHARS);
    }
}
