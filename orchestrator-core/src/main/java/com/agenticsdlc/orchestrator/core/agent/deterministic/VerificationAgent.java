package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.ContentKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.FileChange;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.Operation;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport.BuildOutcome;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport.Traceability;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.BuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Deterministic verifier ({@code verify}): structural checks over the change set against the design and the
 * test plan ({@code paths-within-allowed-roots}, {@code no-secrets}, {@code tests-accompany-production-changes},
 * {@code migration-present-when-schema-changes}, {@code api-changes-implemented}, {@code content-present},
 * {@code traceability}) and, when enabled and a sandbox exists, a real build. Every failed check produces
 * imperative feedback for the implementation stage.
 */
public final class VerificationAgent implements StageAgent {

    /** Allowed path roots of a change set. */
    public static final List<String> ALLOWED_ROOTS = List.of("url-shortener/", "orchestrator-core/",
            "orchestrator-app/", "docs/");

    private final @Nullable BuildRunner buildRunner;

    /**
     * Creates the agent without a build runner (structural verification only).
     */
    public VerificationAgent() {
        this(null);
    }

    /**
     * Creates the agent.
     *
     * @param buildRunner runner for sandbox builds, or null
     */
    public VerificationAgent(@Nullable BuildRunner buildRunner) {
        this.buildRunner = buildRunner;
    }

    @Override
    public AgentRole role() {
        return AgentRole.VERIFICATION;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        ChangeSet changeSet = context.require(ChangeSet.class);
        DesignSpec design = context.require(DesignSpec.class);
        TestPlan testPlan = context.require(TestPlan.class);
        RequirementSpec spec = context.require(RequirementSpec.class);
        BuildOutcome build = null;
        if (context.settings().verifyBuildEnabled() && buildRunner != null) {
            Path sandbox = WorkspaceSandbox.pathFor(context.settings().sandboxRoot(), context.runId());
            if (Files.isDirectory(sandbox)) {
                build = build(sandbox, changeSet.buildCommand(), context);
            }
        }
        VerificationReport report = verify(changeSet, design, testPlan, spec, build);
        long failed = report.structuralChecks().stream().filter(c -> !c.passed()).count();
        return StageResult.success(report, report.passed() ? "all " + report.structuralChecks().size()
                + " checks passed" : failed + " checks failed; " + report.feedbackForImplementation().size()
                + " feedback items for implementation");
    }

    private BuildOutcome build(Path sandbox, String command, StageContext context) throws StageFailure {
        try {
            BuildResult result = buildRunner.run(sandbox, command, context.settings().buildTimeout());
            return new BuildOutcome(result.executed(), result.command(), result.exitCode(), result.durationMs(),
                    result.summary(), result.failures());
        } catch (IllegalArgumentException e) {
            return new BuildOutcome(false, command, -1, 0L, "build not allowed: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw StageFailure.transientFailure("build interrupted");
        }
    }

    /**
     * Runs the structural checks.
     *
     * @param changeSet change set
     * @param design design
     * @param testPlan test plan
     * @param spec requirement specification
     * @param build build outcome or null
     * @return the report
     */
    public VerificationReport verify(ChangeSet changeSet, DesignSpec design, TestPlan testPlan, RequirementSpec spec,
                                     @Nullable BuildOutcome build) {
        List<Check> checks = new ArrayList<>();
        List<String> feedback = new ArrayList<>();

        List<String> outside = changeSet.changes().stream().map(FileChange::path)
                .filter(p -> ALLOWED_ROOTS.stream().noneMatch(p::startsWith)).toList();
        checks.add(outside.isEmpty() ? Check.pass("paths-within-allowed-roots", "all "
                + changeSet.changes().size() + " paths are under " + String.join(", ", ALLOWED_ROOTS))
                : Check.fail("paths-within-allowed-roots", "outside allowed roots: " + String.join(", ", outside)));
        if (!outside.isEmpty()) {
            feedback.add("Move these files under an allowed root (" + String.join(", ", ALLOWED_ROOTS) + "): "
                    + String.join(", ", outside));
        }

        List<String> secretFiles = changeSet.changes().stream()
                .filter(c -> SecretPatterns.containsSecret(c.content()) || SecretPatterns.containsSecret(c.path()))
                .map(FileChange::path).toList();
        checks.add(secretFiles.isEmpty() ? Check.pass("no-secrets", "no credential patterns found")
                : Check.fail("no-secrets", "credential patterns found in: " + String.join(", ", secretFiles)));
        if (!secretFiles.isEmpty()) {
            feedback.add("Remove credentials from: " + String.join(", ", secretFiles)
                    + " (read them from configuration or a secret store)");
        }

        String risks = String.join(" ", changeSet.risks()).toLowerCase(Locale.ROOT);
        List<String> untested = new ArrayList<>();
        for (FileChange change : changeSet.changes()) {
            if (change.operation() == Operation.DELETE || !change.path().endsWith(".java")
                    || !change.path().contains("src/main/")) {
                continue;
            }
            String type = change.fileName().replace(".java", "");
            boolean tested = changeSet.changes().stream().anyMatch(t -> t.path().contains("src/test/")
                    && t.fileName().contains(type));
            boolean justified = risks.contains("test justification") && risks.contains(type.toLowerCase(Locale.ROOT));
            if (!tested && !justified) {
                untested.add(type);
            }
        }
        checks.add(untested.isEmpty() ? Check.pass("tests-accompany-production-changes",
                "every production change has a test or a documented justification")
                : Check.fail("tests-accompany-production-changes", "no test for: " + String.join(", ", untested)));
        if (!untested.isEmpty()) {
            feedback.add("Add tests for: " + String.join(", ", untested));
        }

        boolean schemaChange = !design.dataModelChanges().isEmpty();
        boolean migration = changeSet.changes().stream().anyMatch(c -> c.path().contains("db/migration/")
                && c.path().endsWith(".sql"));
        checks.add(!schemaChange || migration ? Check.pass("migration-present-when-schema-changes",
                schemaChange ? "migration present for the data model changes" : "no data model change")
                : Check.fail("migration-present-when-schema-changes", "design changes the data model but the change "
                + "set has no Flyway migration"));
        if (schemaChange && !migration) {
            feedback.add("Add a Flyway migration under src/main/resources/db/migration for the data model changes");
        }

        List<FileChange> controllers = changeSet.changes().stream()
                .filter(c -> c.fileName().contains("Controller") && c.path().contains("src/main/")).toList();
        Set<String> missingEndpoints = new LinkedHashSet<>();
        for (DesignSpec.ApiChange api : design.apiChanges()) {
            boolean implemented = controllers.stream().anyMatch(c -> c.summary().contains(api.path())
                    || c.content().contains(api.path()));
            if (!implemented) {
                missingEndpoints.add(api.method() + " " + api.path());
            }
        }
        checks.add(missingEndpoints.isEmpty() ? Check.pass("api-changes-implemented", design.apiChanges().size()
                + " API changes implemented by controllers")
                : Check.fail("api-changes-implemented", "not implemented: " + String.join(", ", missingEndpoints)));
        if (!missingEndpoints.isEmpty()) {
            feedback.add("Implement these endpoints in a controller: " + String.join(", ", missingEndpoints));
        }

        List<String> empty = changeSet.changes().stream()
                .filter(c -> c.operation() != Operation.DELETE
                        && (c.contentKind() == ContentKind.NONE || c.content().isBlank()))
                .map(FileChange::path).toList();
        checks.add(empty.isEmpty() ? Check.pass("content-present", "every created or modified file has content")
                : Check.fail("content-present", "no content for: " + String.join(", ", empty)));
        if (!empty.isEmpty()) {
            feedback.add("Provide content for: " + String.join(", ", empty));
        }

        List<String> uncovered = new ArrayList<>();
        for (RequirementSpec.Requirement fr : spec.functional()) {
            boolean traced = testPlan.cases().stream().anyMatch(c -> c.traces().contains(fr.id()));
            if (!traced) {
                uncovered.add(fr.id());
            }
        }
        Traceability traceability = new Traceability(spec.functional().size(),
                spec.functional().size() - uncovered.size(), uncovered);
        checks.add(uncovered.isEmpty() ? Check.pass("traceability", "every functional requirement is traced by "
                + "at least one test case") : Check.fail("traceability", "untraced: " + String.join(", ", uncovered)));
        if (!uncovered.isEmpty()) {
            feedback.add("Add test cases tracing: " + String.join(", ", uncovered));
        }
        if (build != null && build.exitCode() != 0) {
            feedback.add("Fix the build: " + build.summary());
        }
        boolean passed = VerificationReport.computePassed(checks, traceability, build);
        return new VerificationReport(checks, traceability, build, passed, feedback);
    }
}
