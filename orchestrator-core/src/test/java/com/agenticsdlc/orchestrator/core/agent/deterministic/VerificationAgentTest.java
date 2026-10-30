package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.ContentKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.FileChange;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.Operation;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

class VerificationAgentTest {

    private static final String REQUIREMENT = "Build a URL shortener service: create short links, redirect with HTTP "
            + "302 and record click analytics. The service must prevent redirects to private networks.";

    private final RequirementSpec spec = new RequirementsAgent().analyze(REQUIREMENT, List.of(), Map.of(), null,
            WorkspaceMode.NONE, 0.5);
    private final TaskPlan plan = new PlanningAgent().plan(spec);
    private final ImpactAnalysis impact = new com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer()
            .proposeGreenfield(spec);
    private final DesignSpec design = new ArchitectureAgent().design(spec, impact);
    private final ChangeSet changeSet = new ImplementationAgent().implement(design, spec, impact, plan, List.of());
    private final TestPlan testPlan = new TestDesignAgent().plan(spec, design);
    private final VerificationAgent agent = new VerificationAgent();

    private static Check check(VerificationReport report, String name) {
        return report.structuralChecks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private ChangeSet with(List<FileChange> changes) {
        return new ChangeSet(changeSet.baseRevision(), changes, changeSet.buildCommand(), List.of(),
                changeSet.rollbackPlan(), changeSet.traces());
    }

    @Test
    void deterministicChangeSetPassesEveryCheck() {
        VerificationReport report = agent.verify(changeSet, design, testPlan, spec, null);
        assertThat(report.structuralChecks()).extracting(Check::name).containsExactly("paths-within-allowed-roots",
                "no-secrets", "tests-accompany-production-changes", "migration-present-when-schema-changes",
                "api-changes-implemented", "content-present", "traceability");
        assertThat(report.structuralChecks()).allSatisfy(c -> assertThat(c.passed()).as(c.name()).isTrue());
        assertThat(report.passed()).isTrue();
        assertThat(report.feedbackForImplementation()).isEmpty();
        assertThat(report.traceability().uncovered()).isEmpty();
        report.validate();
    }

    @Test
    void reportsEveryFailedCheckWithImperativeFeedback() {
        List<FileChange> changes = new ArrayList<>();
        changes.add(new FileChange("/etc/passwd", Operation.MODIFY, "text", "bad path", ContentKind.FULL, "x",
                List.of()));
        changes.add(new FileChange("url-shortener/src/main/java/com/x/api/LinkController.java", Operation.CREATE,
                "java", "controller without endpoints", ContentKind.FULL, "String key = \"AKIAABCDEFGHIJKLMNOP\";",
                List.of()));
        changes.add(new FileChange("url-shortener/src/main/java/com/x/Empty.java", Operation.CREATE, "java", "empty",
                ContentKind.NONE, "", List.of()));
        VerificationReport report = agent.verify(with(changes), design, testPlan, spec, null);

        assertThat(report.passed()).isFalse();
        assertThat(check(report, "paths-within-allowed-roots").passed()).isFalse();
        assertThat(check(report, "no-secrets").passed()).isFalse();
        assertThat(check(report, "no-secrets").details()).doesNotContain("AKIA");
        assertThat(check(report, "tests-accompany-production-changes").details()).contains("LinkController", "Empty");
        assertThat(check(report, "migration-present-when-schema-changes").passed()).isFalse();
        assertThat(check(report, "api-changes-implemented").details()).contains("POST /api/v1/links");
        assertThat(check(report, "content-present").details()).contains("Empty.java");
        assertThat(report.feedbackForImplementation()).anySatisfy(f -> assertThat(f).startsWith("Move these files"))
                .anySatisfy(f -> assertThat(f).startsWith("Remove credentials"))
                .anySatisfy(f -> assertThat(f).startsWith("Add tests for: LinkController, Empty"))
                .anySatisfy(f -> assertThat(f).startsWith("Add a Flyway migration"))
                .anySatisfy(f -> assertThat(f).startsWith("Implement these endpoints"))
                .anySatisfy(f -> assertThat(f).startsWith("Provide content for"));
        report.validate();
    }

    @Test
    void untracedRequirementsFailTraceabilityAndAJustificationAcceptsMissingTests() {
        TestPlan partial = new TestPlan(testPlan.strategy(), testPlan.levels(), testPlan.cases().stream()
                .filter(c -> !c.traces().contains("FR-2")).toList(), Map.of(), List.of());
        VerificationReport report = agent.verify(changeSet, design, partial, spec, null);
        assertThat(report.traceability().uncovered()).containsExactly("FR-2");
        assertThat(report.traceability().requirementsCovered()).isEqualTo(spec.functional().size() - 1);
        assertThat(report.passed()).isFalse();
        assertThat(report.feedbackForImplementation()).contains("Add test cases tracing: FR-2");

        FileChange production = new FileChange("url-shortener/src/main/java/com/x/Plain.java", Operation.CREATE,
                "java", "plain", ContentKind.FULL, "class Plain {}", List.of());
        ChangeSet justified = new ChangeSet("HEAD", List.of(production), "./mvnw verify",
                List.of("Test justification: Plain is a marker type"), "revert", List.of());
        DesignSpec noApi = new DesignSpec(design.overview(), design.components(), List.of(), List.of(), List.of(),
                List.of(), design.securityConsiderations(), design.dataHandling(), design.alternativesConsidered(),
                design.risk(), design.decisions());
        assertThat(check(agent.verify(justified, noApi, testPlan, spec, null), "tests-accompany-production-changes")
                .passed()).isTrue();
    }

    @Test
    void failedBuildFailsVerification() {
        VerificationReport.BuildOutcome failed = new VerificationReport.BuildOutcome(true, "./mvnw verify", 1, 10,
                "Tests run: 3, Failures: 1, Errors: 0", List.of("FooTest.bar <<< FAILURE!"));
        VerificationReport report = agent.verify(changeSet, design, testPlan, spec, failed);
        assertThat(report.passed()).isFalse();
        assertThat(report.feedbackForImplementation()).containsExactly("Fix the build: Tests run: 3, Failures: 1, "
                + "Errors: 0");
        report.validate();
    }

    @Test
    void runsTheSandboxBuildWhenEnabled(@TempDir Path sandboxRoot) throws Exception {
        Files.createDirectories(WorkspaceSandbox.pathFor(sandboxRoot, "run-1"));
        List<String> commands = new ArrayList<>();
        VerificationAgent withBuild = new VerificationAgent((dir, command, timeout) -> {
            commands.add(command + " in " + dir.getFileName());
            return new BuildResult(true, command, 0, 42, "Tests run: 10, Failures: 0, Errors: 0", List.of(), List.of());
        });
        StageContext base = Contexts.of(AgentRole.VERIFICATION, REQUIREMENT, RunOptions.defaults(), null, List.of(),
                spec, design, changeSet, testPlan);
        StageContext context = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null, null,
                base.options(), List.of(), new AgentSettings(0.5, true, true, null, sandboxRoot, Duration.ofMinutes(1)));
        StageResult result = withBuild.execute(context);
        assertThat(commands).containsExactly("./mvnw -pl url-shortener verify in run-1");
        assertThat(result).isInstanceOfSatisfying(StageResult.Success.class, s -> {
            VerificationReport report = (VerificationReport) s.payload();
            assertThat(report.build()).isNotNull();
            assertThat(report.build().exitCode()).isZero();
            assertThat(report.passed()).isTrue();
        });
    }
}
