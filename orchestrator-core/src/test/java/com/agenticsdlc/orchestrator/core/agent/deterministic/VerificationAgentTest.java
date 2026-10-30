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
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

class VerificationAgentTest {

    private static final String REQUIREMENT = "Build a URL shortener service: create short links, redirect with HTTP "
            + "302 and record click analytics. The service must prevent redirects to private networks.";
    private static final String CONTROLLER = "url-shortener/src/main/java/com/x/api/LinkController.java";
    private static final String CONTROLLER_TEST = "url-shortener/src/test/java/com/x/api/LinkControllerTest.java";

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

    /** A design with a single endpoint and no data model change. */
    private DesignSpec designWith(String method, String path) {
        return new DesignSpec(design.overview(), design.components(), List.of(new DesignSpec.ApiChange(method, path,
                "bulk creation", "", "", List.of(), false)), List.of(), List.of(), List.of(),
                design.securityConsiderations(), design.dataHandling(), design.alternativesConsidered(), design.risk(),
                design.decisions());
    }

    private static FileChange controller(ContentKind kind, String content) {
        return new FileChange(CONTROLLER, Operation.MODIFY, "java", "controller", kind, content, List.of());
    }

    private static FileChange controllerTest(String content) {
        return new FileChange(CONTROLLER_TEST, Operation.MODIFY, "java", "tests", ContentKind.FULL, content,
                List.of());
    }

    @Test
    void deterministicSkeletonChangeSetPassesEveryCheckAsAPlan() {
        VerificationReport report = agent.verify(changeSet, design, testPlan, spec, null);
        assertThat(report.structuralChecks()).extracting(Check::name).containsExactly("paths-within-allowed-roots",
                "no-secrets", "tests-accompany-production-changes", "tests-are-not-vacuous",
                "migration-present-when-schema-changes", "api-changes-planned", "content-present", "traceability");
        assertThat(report.structuralChecks()).allSatisfy(c -> assertThat(c.passed()).as(c.name()).isTrue());
        assertThat(check(report, "api-changes-planned").details()).contains("planned").contains("not implemented");
        assertThat(report.passed()).isTrue();
        assertThat(report.skeletonOnly()).isTrue();
        assertThat(report.notes()).containsExactly(VerificationReport.NO_BUILD_NOTE, VerificationAgent.SKELETON_NOTE);
        assertThat(report.feedbackForImplementation()).isEmpty();
        assertThat(report.traceability().uncovered()).isEmpty();
        assertThat(report.markdown()).contains("skeleton change plan").contains(VerificationReport.NO_BUILD_NOTE);
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
        assertThat(report.skeletonOnly()).isFalse();
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
    void onlyTheWorkloadRootsAreAllowed() {
        assertThat(VerificationAgent.ALLOWED_ROOTS).containsExactly("url-shortener/", "docs/");
        List<FileChange> changes = new ArrayList<>(changeSet.changes());
        changes.add(new FileChange("orchestrator-core/src/main/java/com/x/Engine.java", Operation.MODIFY, "java",
                "self-modification", ContentKind.FULL, "class Engine {}", List.of()));
        changes.add(new FileChange("orchestrator-app/src/main/resources/application.yml", Operation.MODIFY, "yaml",
                "config", ContentKind.FULL, "x: 1", List.of()));
        changes.add(new FileChange("./docs/runbook.md", Operation.MODIFY, "markdown", "docs", ContentKind.FULL,
                "# Runbook", List.of()));
        VerificationReport report = agent.verify(with(changes), design, testPlan, spec, null);
        assertThat(check(report, "paths-within-allowed-roots").details())
                .contains("orchestrator-core/src/main/java/com/x/Engine.java")
                .contains("orchestrator-app/src/main/resources/application.yml").doesNotContain("runbook");
        assertThat(report.passed()).isFalse();
    }

    @Test
    void apiChangesMustAppearInRequestMappingsOfCompleteCode() {
        DesignSpec bulk = designWith("POST", "/api/v1/links/bulk");
        FileChange test = controllerTest("class LinkControllerTest { void t() { assertThat(1).isOne(); } }");
        String classLevel = """
                @RestController
                @RequestMapping("/api/v1/links")
                public class LinkController {
                    @PostMapping(value = "/bulk", consumes = "application/json")
                    public Object bulk(@RequestBody Object request) { return null; }
                }
                """;
        VerificationReport implemented = agent.verify(with(List.of(controller(ContentKind.FULL, classLevel), test)),
                bulk, testPlan, spec, null);
        assertThat(check(implemented, "api-changes-implemented").passed()).isTrue();
        assertThat(implemented.skeletonOnly()).isFalse();
        assertThat(implemented.notes()).containsExactly(VerificationReport.NO_BUILD_NOTE);

        // the path only appears in a comment and a string, not in a mapping annotation
        String mentioned = """
                @RestController
                public class LinkController {
                    // @PostMapping("/api/v1/links/bulk")
                    private static final String DOC = "/api/v1/links/bulk";
                }
                """;
        VerificationReport notMapped = agent.verify(with(List.of(controller(ContentKind.FULL, mentioned), test)),
                bulk, testPlan, spec, null);
        assertThat(check(notMapped, "api-changes-implemented").passed()).isFalse();
        assertThat(notMapped.feedbackForImplementation()).anySatisfy(f -> assertThat(f)
                .startsWith("Implement these endpoints").contains("POST /api/v1/links/bulk"));

        String wrongMethod = classLevel.replace("@PostMapping", "@PutMapping");
        assertThat(check(agent.verify(with(List.of(controller(ContentKind.FULL, wrongMethod), test)), bulk, testPlan,
                spec, null), "api-changes-implemented").passed()).isFalse();

        String requestMapping = """
                @RestController
                public class LinkController {
                    @RequestMapping(path = "/api/v1/links/bulk", method = RequestMethod.POST)
                    public Object bulk() { return null; }
                }
                """;
        assertThat(check(agent.verify(with(List.of(controller(ContentKind.FULL, requestMapping), test)), bulk,
                testPlan, spec, null), "api-changes-implemented").passed()).isTrue();

        DesignSpec byCode = designWith("GET", "/api/v1/links/{code}/stats");
        String pathVariable = """
                @RestController
                @RequestMapping({"/api/v1/links"})
                public class LinkController {
                    @GetMapping("/{id}/stats")
                    public Object stats(@PathVariable String id) { return null; }
                }
                """;
        assertThat(check(agent.verify(with(List.of(controller(ContentKind.FULL, pathVariable), test)), byCode,
                testPlan, spec, null), "api-changes-implemented").passed()).as("path variable names are ignored")
                .isTrue();

        // a diff whose hunk does not include the class-level mapping
        String diff = """
                --- a/url-shortener/src/main/java/com/x/api/LinkController.java
                +++ b/url-shortener/src/main/java/com/x/api/LinkController.java
                @@ -20,6 +20,11 @@ public class LinkController {
                     }

                +    @PostMapping("/bulk")
                +    public Object bulk(@RequestBody Object request) {
                +        return linkService.createAll(request);
                +    }
                 }
                """;
        assertThat(check(agent.verify(with(List.of(controller(ContentKind.DIFF, diff), test)), bulk, testPlan, spec,
                null), "api-changes-implemented").passed()).isTrue();
        String removed = diff.replace("+    @PostMapping(\"/bulk\")", "-    @PostMapping(\"/bulk\")");
        assertThat(check(agent.verify(with(List.of(controller(ContentKind.DIFF, removed), test)), bulk, testPlan,
                spec, null), "api-changes-implemented").passed()).as("removed lines do not count").isFalse();
    }

    @Test
    void skeletonContentIsReportedAsPlannedNotImplemented() {
        DesignSpec bulk = designWith("POST", "/api/v1/links/bulk");
        FileChange test = controllerTest("class LinkControllerTest { void t() { fail(\"TODO\"); } }");
        FileChange skeleton = new FileChange(CONTROLLER, Operation.MODIFY, "java", "Endpoints: POST /api/v1/links/bulk",
                ContentKind.SKELETON, "class LinkController { /* TODO */ }", List.of());
        VerificationReport report = agent.verify(with(List.of(skeleton, test)), bulk, testPlan, spec, null);
        assertThat(report.structuralChecks()).extracting(Check::name).contains("api-changes-planned")
                .doesNotContain("api-changes-implemented");
        assertThat(check(report, "api-changes-planned").passed()).isTrue();
        assertThat(report.skeletonOnly()).isTrue();

        FileChange unrelated = new FileChange(CONTROLLER, Operation.MODIFY, "java", "other work", ContentKind.SKELETON,
                "class LinkController { /* TODO */ }", List.of());
        VerificationReport missing = agent.verify(with(List.of(unrelated, test)), bulk, testPlan, spec, null);
        assertThat(check(missing, "api-changes-planned").passed()).isFalse();
        assertThat(check(missing, "api-changes-planned").details()).contains("not planned: POST /api/v1/links/bulk");
    }

    @Test
    void testFilesWithoutAssertionsAreVacuous() {
        DesignSpec noApi = designWith("POST", "/x");
        FileChange production = controller(ContentKind.FULL, "@PostMapping(\"/x\") Object x() { return null; }");
        VerificationReport vacuous = agent.verify(with(List.of(production, controllerTest("""
                class LinkControllerTest {
                    @Test
                    void createsLinks() {
                        // assert that the link exists
                        new LinkController();
                    }
                }
                """))), noApi, testPlan, spec, null);
        assertThat(check(vacuous, "tests-are-not-vacuous").passed()).isFalse();
        assertThat(check(vacuous, "tests-are-not-vacuous").details()).contains(CONTROLLER_TEST);
        assertThat(vacuous.feedbackForImplementation()).anySatisfy(f -> assertThat(f).startsWith("Add real assertions")
                .contains(CONTROLLER_TEST));
        assertThat(vacuous.passed()).isFalse();

        for (String body : List.of("assertThat(link).isNotNull();", "Assertions.assertEquals(1, 1);",
                "org.junit.jupiter.api.Assertions.fail(\"TODO: implement createsLinks\");",
                "mvc.perform(post(\"/x\")).andExpect(status().isOk());", "verify(service).create(any());")) {
            VerificationReport ok = agent.verify(with(List.of(production, controllerTest(
                    "class LinkControllerTest {\n    @Test\n    void createsLinks() {\n        " + body + "\n    }\n}\n"))),
                    noApi, testPlan, spec, null);
            assertThat(check(ok, "tests-are-not-vacuous").passed()).as(body).isTrue();
        }
        // the deterministic skeleton tests fail explicitly until implemented
        assertThat(changeSet.changes()).filteredOn(c -> c.path().contains("src/test/")).isNotEmpty()
                .allSatisfy(c -> assertThat(c.content()).contains("org.junit.jupiter.api.Assertions.fail(\"TODO: "));
    }

    @Test
    void createOfAnIndexedFileFailsWhenACodebaseIndexIsAvailable(@TempDir Path dir) {
        CodebaseIndex index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir));
        String existingTest = "url-shortener/src/test/java/com/agenticsdlc/shortener/api/LinkControllerTest.java";
        FileChange newFile = new FileChange("url-shortener/src/main/java/com/agenticsdlc/shortener/api/"
                + "BulkController.java", Operation.CREATE, "java", "new", ContentKind.FULL, "class BulkController {}",
                List.of());
        String testContent = "class LinkControllerTest { void t() { assertThat(1).isOne(); } }";
        VerificationReport report = agent.verify(with(List.of(newFile, new FileChange(existingTest, Operation.CREATE,
                "java", "overwrite", ContentKind.FULL, testContent, List.of()))), design, testPlan, spec, null, index);
        assertThat(check(report, "no-create-over-existing-file").passed()).isFalse();
        assertThat(check(report, "no-create-over-existing-file").details()).contains(existingTest)
                .doesNotContain("BulkController");
        assertThat(report.feedbackForImplementation()).anySatisfy(f -> assertThat(f).startsWith("Use MODIFY")
                .contains(existingTest));
        assertThat(report.passed()).isFalse();

        VerificationReport clean = agent.verify(with(List.of(newFile, new FileChange("./" + existingTest,
                Operation.MODIFY, "java", "more tests", ContentKind.FULL, testContent, List.of()))), design, testPlan,
                spec, null, index);
        assertThat(check(clean, "no-create-over-existing-file").passed()).isTrue();
        assertThat(agent.verify(changeSet, design, testPlan, spec, null).structuralChecks()).extracting(Check::name)
                .as("skipped without an index").doesNotContain("no-create-over-existing-file");
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
        assertThat(report.notes()).doesNotContain(VerificationReport.NO_BUILD_NOTE);
        report.validate();
    }

    @Test
    void runsTheEngineConfiguredBuildCommandAndIgnoresTheAgentSuppliedOne(@TempDir Path sandboxRoot)
            throws Exception {
        Files.createDirectories(WorkspaceSandbox.pathFor(sandboxRoot, "run-1"));
        List<String> commands = new ArrayList<>();
        VerificationAgent withBuild = new VerificationAgent((dir, command, timeout) -> {
            commands.add(command + " in " + dir.getFileName());
            return new BuildResult(true, command, 0, 42, "Tests run: 10, Failures: 0, Errors: 0", List.of(), List.of());
        });
        ChangeSet hostile = new ChangeSet(changeSet.baseRevision(), changeSet.changes(), "mvn -f ../evil/pom.xml "
                + "exec:exec", changeSet.risks(), changeSet.rollbackPlan(), changeSet.traces());
        StageContext base = Contexts.of(AgentRole.VERIFICATION, REQUIREMENT, RunOptions.defaults(), null, List.of(),
                spec, design, hostile, testPlan);
        StageContext context = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null, null,
                base.options(), List.of(), new AgentSettings(0.5, true, true, null, sandboxRoot, Duration.ofMinutes(1)));
        StageResult result = withBuild.execute(context);
        assertThat(AgentSettings.defaults().buildCommand()).isEqualTo("./mvnw -B -ntp -q verify");
        assertThat(commands).containsExactly("./mvnw -B -ntp -q verify in run-1");
        assertThat(result).isInstanceOfSatisfying(StageResult.Success.class, s -> {
            VerificationReport report = (VerificationReport) s.payload();
            assertThat(report.build()).isNotNull();
            assertThat(report.build().exitCode()).isZero();
            assertThat(report.build().command()).isEqualTo("./mvnw -B -ntp -q verify");
            assertThat(report.passed()).isTrue();
            assertThat(report.notes()).doesNotContain(VerificationReport.NO_BUILD_NOTE);
        });

        commands.clear();
        StageContext custom = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null, null,
                base.options(), List.of(), new AgentSettings(0.5, true, true, null, sandboxRoot, Duration.ofMinutes(1),
                "mvn -q -pl url-shortener verify"));
        withBuild.execute(custom);
        assertThat(commands).containsExactly("mvn -q -pl url-shortener verify in run-1");
    }
}
