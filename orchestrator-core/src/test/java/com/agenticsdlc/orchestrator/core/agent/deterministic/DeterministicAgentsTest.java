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
