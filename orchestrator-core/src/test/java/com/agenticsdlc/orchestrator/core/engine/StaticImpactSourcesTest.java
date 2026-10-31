package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.BASE;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.CREATE_REQUEST;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.LINK;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.LINK_CONTROLLER;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.LINK_SERVICE;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.MIGRATIONS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmClient;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmStageAgent;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.testsupport.Runs;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources;

/**
 * REPO runs: the deterministic static impact analysis informs the LLM impact and design stages and the choice of
 * impacted sources; files named in feedback, failed checks and errors, and files an implementer requests, are shown in
 * the next attempt; STAGE_STARTED records which sources an attempt was shown.
 */
class StaticImpactSourcesTest {

    private static final RunOptions REPO = RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)
            .withWorkspace(WorkspaceMode.REPO);
    private static final String V1 = MIGRATIONS + "V1__init.sql";

    /** A provider that answers every request with one scripted payload. */
    static final class ScriptedClient implements LlmClient {
        final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final Object answer;

        ScriptedClient(Object answer) {
            this.answer = answer;
        }

        @Override
        public String providerId() {
            return "fake-llm";
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> LlmResponse<T> complete(LlmRequest request, Class<T> outputType) {
            requests.add(request);
            return new LlmResponse<>((T) answer, "{}", new LlmUsage(100, 50), 5, "fake-model");
        }
    }

    private final EngineHarness harness = new EngineHarness();
    private Path workspace;
    private CodebaseIndex index;

    @BeforeEach
    void index(@TempDir Path dir) {
        workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        index = new CodebaseIndexer().index(workspace);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private static List<AuditEvent> started(List<AuditEvent> events, String stageId) {
        return events.stream().filter(e -> e.type() == AuditEventType.STAGE_STARTED && stageId.equals(e.stageId()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(AuditEvent event, String key) {
        return (List<String>) event.data().get(key);
    }

    @Test
    void anLlmImpactListingOnlyResourcesIsReconciledAndTheImplementerSeesTheJavaSources() {
        ScriptedClient client = new ScriptedClient(CampaignFixture.resourcesOnlyImpact());
        FakeAgent implement = FakeAgent.succeeding(AgentRole.IMPLEMENTATION);
        FakeAgent verify = FakeAgent.succeeding(AgentRole.VERIFICATION);
        harness.agent(new FakeAgent(AgentRole.REQUIREMENTS, (ctx, call) -> StageResult.success(
                        CampaignFixture.spec(), "spec")))
                .agent(implement).agent(verify)
                .provider("fake-llm", LlmStageAgent.forRole(AgentRole.CODEBASE_ANALYSIS, client, JsonCodec.standard(),
                        4000, Duration.ofSeconds(30)))
                .config(b -> b.workspaceRoot(workspace).workspaceIndex(() -> index));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(CampaignFixture.REQUIREMENT, "campaign", REPO.withProvider("fake-llm")).id();

        Run run = harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);

        assertThat(client.requests).hasSize(1);
        assertThat(client.requests.getFirst().user()).contains("## Components found by static analysis")
                .contains(LINK).contains(LINK_SERVICE);
        ImpactAnalysis impact = run.acceptedPayload(ImpactAnalysis.class).orElseThrow();
        assertThat(impact.components()).as("the static Link and LinkService components were merged with paths")
                .anySatisfy(c -> {
                    assertThat(c.name()).isEqualTo("Link");
                    assertThat(c.path()).isEqualTo(LINK);
                    assertThat(c.nature()).isEqualTo(ChangeNature.EXTEND);
                })
                .anySatisfy(c -> {
                    assertThat(c.name()).isEqualTo("LinkService");
                    assertThat(c.path()).isEqualTo(LINK_SERVICE);
                });
        assertThat(impact.components()).extracting(ImpactedComponent::nature).doesNotContainNull();
        assertThat(impact.entities()).contains("Link (links)");
        assertThat(impact.reconciliation()).anySatisfy(n -> assertThat(n).startsWith(
                "merged from static analysis: Link (ENTITY, EXTEND"));

        StageContext first = implement.contexts().getFirst();
        assertThat(first.staticImpact()).isNotNull();
        assertThat(first.impactedSources()).containsKeys(LINK, LINK_SERVICE, LINK_CONTROLLER, V1);
        assertThat(first.impactedSources().get(LINK)).contains("public class Link");

        List<AuditEvent> events = harness.events(id);
        AuditEvent implementStarted = started(events, WorkflowDefinitions.IMPLEMENT).getFirst();
        List<String> shown = list(implementStarted, "impactedSources");
        assertThat(shown).as("the paths shown, in reading order").containsExactlyInAnyOrderElementsOf(
                first.impactedSources().keySet());
        assertThat(shown.indexOf(LINK)).as("confident static components before a low-confidence MODIFY component")
                .isLessThan(shown.indexOf(V1));
        assertThat(list(implementStarted, "impactedSourceNotes")).isEqualTo(first.impactedSourceNotes());
        assertThat(list(started(events, WorkflowDefinitions.VERIFY).getFirst(), "impactedSources")).contains(LINK);
        assertThat(list(started(events, WorkflowDefinitions.SECURITY_SCAN).getFirst(), "impactedSources"))
                .contains(LINK);
        assertThat(started(events, WorkflowDefinitions.DESIGN).getFirst().data()).doesNotContainKey("impactedSources");
    }

    @Test
    void feedbackFailedChecksAndErrorsSelectTheFilesTheyNameByTypeOrPath() {
        Run run = Runs.create("Create short links.", REPO);
        List<String> feedback = List.of("[verify@v1] Add a nullable campaign field to the Link entity, set it in "
                + "LinkService.create and accept it in CreateLinkRequest");

        List<ImpactedSources.Request> requests = StageExecutor.impactedSourceRequests(run, feedback, null, index,
                null);

        assertThat(requests).extracting(ImpactedSources.Request::path).containsExactly(LINK, LINK_SERVICE,
                CREATE_REQUEST);
        assertThat(requests).extracting(ImpactedSources.Request::priority)
                .containsOnly(ImpactedSources.Priority.MODIFY);
        assertThat(StageExecutor.impactedSourceRequests(run, List.of(), "INVALID_OUTPUT: hunk 1/1 of "
                + BASE + "link/Link.java does not apply", index, null)).as("a wrong package resolves to the indexed file")
                .extracting(ImpactedSources.Request::path).containsExactly(LINK);

        run.addArtifact(Contexts.artifact(new VerificationReport(List.of(
                Check.fail("campaign-persisted", "ClickEvent does not carry the campaign"),
                Check.advisory("style", false, "RateLimitFilter naming")), new VerificationReport.Traceability(1, 1,
                List.of()), null, false, List.of("Persist the campaign label"))));
        assertThat(StageExecutor.impactedSourceRequests(run, List.of("Persist the campaign label"), null, index, null))
                .as("details of failed blocking checks count, advisory ones do not")
                .extracting(ImpactedSources.Request::path)
                .containsExactly(BASE + "domain/ClickEvent.java");
        assertThat(StageExecutor.impactedSourceRequests(run, List.of(), null, index, null)).as("only in feedback loops")
                .isEmpty();

        String everyType = String.join(" ", index.types().stream().filter(t -> !t.test())
                .map(t -> t.simpleName()).toList());
        assertThat(StageExecutor.impactedSourceRequests(Runs.create("x", REPO), List.of(everyType), null, index, null))
                .hasSize(StageExecutor.MAX_IDENTIFIER_FILES);
    }

    @Test
    void sourcesAChangeSetRequestedAreShownAfterTheVerificationFeedbackLoop() {
        FakeAgent implement = new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            ChangeSet base = Payloads.changeSet("url-shortener/src/main/java/com/example/Feature.java",
                    "class Feature {}");
            return StageResult.success(call == 1 ? new ChangeSet(base.baseRevision(), base.changes(),
                    base.buildCommand(), base.risks(), base.rollbackPlan(), base.traces(),
                    List.of(BASE + "link/Link.java")) : base, "implemented " + call);
        });
        FakeAgent verify = new FakeAgent(AgentRole.VERIFICATION, (ctx, call) -> StageResult.success(
                call == 1 ? new VerificationReport(List.of(Check.fail("campaign-persisted", "the label is lost")),
                        new VerificationReport.Traceability(1, 1, List.of()), null, false,
                        List.of("Persist the campaign label with each link")) : Payloads.verification(true),
                "verified " + call));
        harness.agent(implement).agent(verify).config(b -> b.workspaceRoot(workspace).workspaceIndex(() -> index));
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links.", "requested", REPO).id();

        harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);

        assertThat(implement.contexts()).hasSize(2);
        StageContext first = implement.contexts().getFirst();
        assertThat(first.impactedSources()).doesNotContainKey(LINK)
                .as("the impact artifact names no component: these come from the static analysis alone")
                .containsKeys(LINK_CONTROLLER, LINK_SERVICE);
        StageContext second = implement.contexts().get(1);
        assertThat(second.feedback()).singleElement().asString().contains("Persist the campaign label");
        assertThat(second.impactedSources()).as("the requested wrong path resolves to the indexed entity")
                .containsKey(LINK);
    }

    @Test
    void sourcesARejectedChangeSetRequestedAreShownInTheRetry() {
        FakeAgent implement = new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) -> {
            if (call == 1) {
                throw new StageFailure(FailureKind.INVALID_OUTPUT, "the workspace sandbox rejected the change set: "
                        + "hunk 1/1 does not apply", null, null, List.of("Link", "docs/unknown, file.md"));
            }
            return StageResult.success(Payloads.forRole(AgentRole.IMPLEMENTATION), "implemented");
        });
        harness.agent(implement).config(b -> b.workspaceRoot(workspace).workspaceIndex(() -> index));
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links.", "rejected", REPO).id();

        harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);

        StageContext retry = implement.contexts().get(1);
        assertThat(retry.previousError()).startsWith("INVALID_OUTPUT: the workspace sandbox rejected")
                .contains("\n" + StageExecutor.REQUESTED_SOURCES_PREFIX + "Link, docs/unknown,file.md");
        assertThat(retry.impactedSources()).containsKey(LINK);
        assertThat(retry.impactedSourceNotes()).contains("docs/unknown,file.md: not found in the workspace");
        AuditEvent scheduled = harness.events(id).stream().filter(e -> e.type() == AuditEventType.RETRY_SCHEDULED)
                .findFirst().orElseThrow();
        assertThat(list(scheduled, "requestedSources")).containsExactly("Link", "docs/unknown,file.md");
        assertThat(StageExecutor.requestedSourcesLine(List.of(" ", ""))).isNull();
        assertThat(StageExecutor.requestedSourcesIn(null)).isEmpty();
    }

    @Test
    void theStaticImpactIsComputedOncePerPlanVersionSpecificationAndIndex() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            StageExecutor executor = new StageExecutor(new WorkflowGraph(WorkflowDefinitions.defaultSdlc()),
                    EngineConfig.builder().build(), pool);
            Run run = Runs.create(CampaignFixture.REQUIREMENT, REPO);
            Map<String, Artifact> upstream = Map.of("intake", Contexts.artifact(CampaignFixture.spec()));

            ImpactAnalysis first = executor.staticImpact(run, upstream, index);
            assertThat(first).isNotNull();
            assertThat(first.components()).extracting(ImpactedComponent::path).contains(LINK, LINK_SERVICE);
            assertThat(executor.staticImpact(run, upstream, index)).as("cached").isSameAs(first);

            run.incrementPlanVersion();
            ImpactAnalysis replanned = executor.staticImpact(run, upstream, index);
            assertThat(replanned).as("recomputed after a re-plan").isNotSameAs(first).isEqualTo(first);

            Artifact clarified = respecified(Payloads.requirementSpec(com.agenticsdlc.orchestrator.core.artifact
                    .RiskLevel.LOW));
            ImpactAnalysis afterClarification = executor.staticImpact(run, Map.of("intake", clarified), index);
            assertThat(afterClarification).as("recomputed for a new specification").isNotEqualTo(first);

            CodebaseIndex reindexed = new CodebaseIndexer().index(workspace);
            assertThat(executor.staticImpact(run, Map.of("intake", clarified), reindexed))
                    .as("recomputed for a new index").isNotSameAs(afterClarification);

            assertThat(executor.staticImpact(run, Map.of(), index)).as("no specification upstream").isNull();
            assertThat(executor.staticImpact(run, upstream, CodebaseIndex.empty())).isNull();
            assertThat(executor.staticImpact(run, upstream, null)).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Artifact respecified(RequirementSpec spec) {
        return new Artifact("artifact-intake-2", "run-1", "intake", spec.kind(), 2, 1, "hash-2", spec,
                new Provenance(Map.of(), AgentRole.REQUIREMENTS, "deterministic", "deterministic", null, null, 0, 0, 0,
                        1, 1), Instant.parse("2026-01-01T00:00:00Z"));
    }
}
