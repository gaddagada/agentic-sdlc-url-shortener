package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

class DeterministicAgentsTest {

    private static final AgentRole[] PIPELINE = {AgentRole.REQUIREMENTS, AgentRole.PLANNING,
            AgentRole.CODEBASE_ANALYSIS, AgentRole.ARCHITECTURE, AgentRole.IMPLEMENTATION, AgentRole.TEST_DESIGN,
            AgentRole.VERIFICATION, AgentRole.DOCUMENTATION, AgentRole.SECURITY_REVIEW, AgentRole.RELEASE};
    private static final String BROWNFIELD = "Add a bulk link creation API to the existing URL shortener: accept up to "
            + "100 URLs per request, return per-item results with partial-failure semantics, reuse the existing URL "
            + "validation and custom alias rules, and respect the existing per-client rate limit.";

    private final JsonCodec codec = JsonCodec.standard();

    private Map<AgentRole, String> runPipeline(String requirement, RunOptions options, CodebaseIndex index)
            throws Exception {
        Map<AgentRole, StageAgent> agents = new EnumMap<>(AgentRole.class);
        DeterministicAgents.all(null).forEach(a -> agents.put(a.role(), a));
        List<ArtifactPayload> upstream = new ArrayList<>();
        Map<AgentRole, String> json = new EnumMap<>(AgentRole.class);
        for (AgentRole role : PIPELINE) {
            StageContext context = Contexts.of(role, requirement, options, index, List.of(),
                    upstream.toArray(ArtifactPayload[]::new));
            StageResult result = agents.get(role).execute(context);
            assertThat(result).as(role.name()).isInstanceOf(StageResult.Success.class);
            ArtifactPayload payload = ((StageResult.Success) result).payload();
            payload.validate();
            assertThat(payload.kind()).isEqualTo(role.artifactKind());
            assertThat(payload.markdown()).startsWith("## ");
            upstream.add(payload);
            json.put(role, codec.canonicalJson(payload));
        }
        return json;
    }

    @Test
    void identicalInputsProduceByteIdenticalCanonicalJson(@TempDir Path dir) throws Exception {
        CodebaseIndex index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir));
        RunOptions repo = RunOptions.defaults().withWorkspace(WorkspaceMode.REPO);
        Map<AgentRole, String> first = runPipeline(BROWNFIELD, repo, index);
        Map<AgentRole, String> second = runPipeline(BROWNFIELD, repo, index);
        assertThat(second).isEqualTo(first);
        assertThat(first).hasSize(10);
        assertThat(first.values()).noneMatch(j -> j.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*"))
                .as("payloads contain no timestamps");

        Map<AgentRole, String> greenfield = runPipeline(DeterministicPipelineFixtures.GREENFIELD, RunOptions.defaults(),
                null);
        assertThat(runPipeline(DeterministicPipelineFixtures.GREENFIELD, RunOptions.defaults(), null))
                .isEqualTo(greenfield);
    }

    @Test
    void implementationGeneratesDdlTestsAndSkeletonsAndRegistersASandboxCompensation(@TempDir Path dir)
            throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        Path sandboxRoot = dir.resolve("sandbox");
        CodebaseIndex index = new CodebaseIndexer().index(workspace);
        Map<AgentRole, StageAgent> agents = new EnumMap<>(AgentRole.class);
        DeterministicAgents.all(null).forEach(a -> agents.put(a.role(), a));
        RunOptions repo = RunOptions.defaults().withWorkspace(WorkspaceMode.REPO);
        List<ArtifactPayload> upstream = new ArrayList<>();
        for (AgentRole role : List.of(AgentRole.REQUIREMENTS, AgentRole.PLANNING, AgentRole.CODEBASE_ANALYSIS,
                AgentRole.ARCHITECTURE)) {
            upstream.add(((StageResult.Success) agents.get(role).execute(Contexts.of(role, BROWNFIELD, repo, index,
                    List.of(), upstream.toArray(ArtifactPayload[]::new)))).payload());
        }
        StageContext base = Contexts.of(AgentRole.IMPLEMENTATION, BROWNFIELD, repo, index, List.of(),
                upstream.toArray(ArtifactPayload[]::new));
        StageContext withSandbox = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(),
                List.of("[verify@v1] Add tests for: BulkCreateLinksRequest"), 1, 1, null, index, repo, List.of(),
                new AgentSettings(0.5, true, false, workspace, sandboxRoot, java.time.Duration.ofMinutes(1)));
        StageResult.Success result = (StageResult.Success) agents.get(AgentRole.IMPLEMENTATION).execute(withSandbox);
        ChangeSet changeSet = (ChangeSet) result.payload();

        assertThat(result.compensations()).singleElement().satisfies((CompensationRequest c) -> {
            assertThat(c.action()).isEqualTo(WorkspaceSandbox.DELETE_ACTION);
            assertThat(Path.of(c.target())).isEqualTo(WorkspaceSandbox.pathFor(sandboxRoot, "run-1"));
        });
        Path sandbox = WorkspaceSandbox.pathFor(sandboxRoot, "run-1");
        assertThat(sandbox.resolve("url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java"))
                .as("existing files are never overwritten by skeletons").content().contains("public class LinkController");
        assertThat(sandbox.resolve("url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java"
                + ".skeleton")).exists();
        assertThat(sandbox.resolve("url-shortener/target")).doesNotExist();
        String existingTest = "url-shortener/src/test/java/com/agenticsdlc/shortener/api/LinkControllerTest.java";
        assertThat(sandbox.resolve(existingTest)).as("existing tests are never overwritten").content()
                .isEqualTo(Files.readString(workspace.resolve(existingTest)));
        assertThat(sandbox.resolve(existingTest + ".skeleton")).content().contains("MODIFY skeleton");
        assertThat(changeSet.changes()).anySatisfy(c -> {
            assertThat(c.fileName()).isEqualTo("BulkCreateLinksRequestTest.java");
        });
        assertThat(changeSet.changes().getFirst().content()).startsWith("// Addressed feedback: [verify@v1] Add tests "
                + "for: BulkCreateLinksRequest");
        DesignSpec design = (DesignSpec) upstream.getLast();
        assertThat(design.apiChanges()).isNotEmpty();
        assertThat(Files.exists(sandbox)).isTrue();
    }

    @Test
    void implementationModifiesFilesThatExistInTheIndexAndNeverCreatesThem(@TempDir Path dir) throws Exception {
        CodebaseIndex index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir));
        Map<AgentRole, StageAgent> agents = new EnumMap<>(AgentRole.class);
        DeterministicAgents.all(null).forEach(a -> agents.put(a.role(), a));
        RunOptions repo = RunOptions.defaults().withWorkspace(WorkspaceMode.REPO);
        List<ArtifactPayload> upstream = new ArrayList<>();
        for (AgentRole role : List.of(AgentRole.REQUIREMENTS, AgentRole.PLANNING, AgentRole.CODEBASE_ANALYSIS,
                AgentRole.ARCHITECTURE)) {
            upstream.add(((StageResult.Success) agents.get(role).execute(Contexts.of(role, BROWNFIELD, repo, index,
                    List.of(), upstream.toArray(ArtifactPayload[]::new)))).payload());
        }
        RequirementSpec spec = (RequirementSpec) upstream.get(0);
        TaskPlan plan = (TaskPlan) upstream.get(1);
        ImpactAnalysis impact = (ImpactAnalysis) upstream.get(2);
        DesignSpec design = (DesignSpec) upstream.get(3);
        ImplementationAgent implementation = new ImplementationAgent();

        ChangeSet changeSet = implementation.implement(design, spec, impact, plan, List.of(), index);

        String existingTest = "url-shortener/src/test/java/com/agenticsdlc/shortener/api/LinkControllerTest.java";
        assertThat(index.containsPath(existingTest)).isTrue();
        assertThat(changeSet.changes()).filteredOn(c -> c.path().equals(existingTest)).singleElement().satisfies(c -> {
            assertThat(c.operation()).isEqualTo(ChangeSet.Operation.MODIFY);
            assertThat(c.contentKind()).isEqualTo(ChangeSet.ContentKind.SKELETON);
            assertThat(c.content()).startsWith("// MODIFY skeleton for the existing test class LinkControllerTest")
                    .contains("keep the existing tests unchanged").doesNotContain("package ")
                    .contains("org.junit.jupiter.api.Assertions.fail(\"TODO: implement ");
        });
        assertThat(changeSet.changes()).filteredOn(c -> c.path().endsWith("api/LinkController.java")).singleElement()
                .satisfies(c -> {
                    assertThat(c.operation()).isEqualTo(ChangeSet.Operation.MODIFY);
                    assertThat(c.content()).contains("// MODIFY skeleton for the existing type LinkController");
                });
        assertThat(changeSet.changes()).filteredOn(c -> index.containsPath(c.path())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.operation()).as(c.path()).isEqualTo(ChangeSet.Operation.MODIFY));
        assertThat(changeSet.changes()).filteredOn(c -> c.operation() == ChangeSet.Operation.CREATE).isNotEmpty()
                .allSatisfy(c -> assertThat(index.containsPath(c.path())).as(c.path()).isFalse());
        assertThat(changeSet.changes()).filteredOn(c -> c.path().contains("src/test/")).isNotEmpty()
                .allSatisfy(c -> assertThat(c.content()).contains("org.junit.jupiter.api.Assertions.fail(\"TODO: "
                        + "implement ").doesNotContainPattern("void \\w+\\(\\) \\{\\s*}"));
        assertThat(changeSet.risks()).contains(ImplementationAgent.SKELETON_RISK);
        assertThat(ImplementationAgent.SKELETON_RISK).startsWith("skeleton implementation; not compilable/complete");
        assertThat(implementation.implement(design, spec, impact, plan, List.of(), index)).as("deterministic")
                .isEqualTo(changeSet);

        ChangeSet withoutIndex = implementation.implement(design, spec, impact, plan, List.of());
        assertThat(withoutIndex.changes()).filteredOn(c -> c.path().equals(existingTest)).singleElement()
                .satisfies(c -> assertThat(c.operation()).as("unknown without an index")
                        .isEqualTo(ChangeSet.Operation.CREATE));

        TestPlan testPlan = new TestDesignAgent().plan(spec, design);
        VerificationReport report = new VerificationAgent().verify(changeSet, design, testPlan, spec, null, index);
        assertThat(report.structuralChecks()).filteredOn(c -> c.name().equals("no-create-over-existing-file"))
                .singleElement().satisfies(c -> assertThat(c.passed()).isTrue());
        assertThat(report.structuralChecks()).filteredOn(c -> c.name().equals("tests-are-not-vacuous"))
                .singleElement().satisfies(c -> assertThat(c.passed()).isTrue());
        assertThat(report.passed()).isTrue();
        assertThat(report.skeletonOnly()).isTrue();
    }

    @Test
    void migrationDdlIsDerivedFromDataModelChanges() {
        assertThat(ImplementationAgent.ddl(new DesignSpec.DataModelChange("Link",
                "create table links (id, code, original_url, created_at, expires_at, click_count, custom_alias)",
                "V1__create_links.sql", true))).isEqualTo("""
                CREATE TABLE links (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    code VARCHAR(32) NOT NULL UNIQUE,
                    original_url VARCHAR(2048) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    expires_at TIMESTAMP NULL,
                    click_count BIGINT NOT NULL DEFAULT 0,
                    custom_alias BOOLEAN NOT NULL DEFAULT FALSE
                );""");
        assertThat(ImplementationAgent.ddl(new DesignSpec.DataModelChange("Link", "add column title to links",
                "V3__title.sql", true))).isEqualTo("ALTER TABLE links ADD COLUMN title VARCHAR(255) NULL;");
    }

    /** Shared scenario texts. */
    static final class DeterministicPipelineFixtures {
        static final String GREENFIELD = "Build a URL shortener service: create short links for HTTP and HTTPS URLs "
                + "with optional expiry, redirect with HTTP 302, record click analytics (total clicks, clicks per day, "
                + "top referrers) and expose health checks. The p95 redirect latency must be under 50 ms at 500 "
                + "requests per second. Links default to no expiry. The service must prevent redirects to private "
                + "networks.";

        private DeterministicPipelineFixtures() {
        }
    }
}
