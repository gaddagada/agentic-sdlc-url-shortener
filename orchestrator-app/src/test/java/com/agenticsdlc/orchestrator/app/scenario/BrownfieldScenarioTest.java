package com.agenticsdlc.orchestrator.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.app.config.WorkspaceRootDetector;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.ChainVerification;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * The {@code brownfield} catalog scenario against the real repository: the deterministic agents reason over the
 * indexed {@code url-shortener} source tree (the workspace root is detected the way the application detects it), the
 * requirement is not routed to clarification, the impact analysis ranks the link and analytics controllers and
 * services, the {@code Link} entity and the Flyway migrations at the top, and the run completes with exactly two
 * human approvals (design and release readiness).
 */
class BrownfieldScenarioTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String MIGRATIONS = "url-shortener/src/main/resources/db/migration/";

    private static Path root;
    private static CodebaseIndex index;

    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private OrchestrationEngine engine;

    @BeforeAll
    static void indexTheRepository() {
        root = WorkspaceRootDetector.detect(Path.of("").toAbsolutePath());
        index = new CodebaseIndexer().index(root);
    }

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void theScenarioAsksForAChangeTheShortenerDoesNotImplementYet() {
        ScenarioDefinition scenario = new ScenarioCatalog().require("brownfield");

        assertThat(scenario.requirement()).isEqualTo(ScenarioCatalog.BROWNFIELD_REQUIREMENT)
                .contains("campaign label", "POST /api/v1/links/bulk", "GET /api/v1/analytics/campaigns/{campaign}",
                        "Flyway migration");
        assertThat(index.types()).as("the bulk endpoint the old scenario asked for exists now")
                .anySatisfy(t -> assertThat(t.simpleName()).isEqualTo("BulkLinkController"));
        assertThat(index.types()).as("no campaign endpoint exists yet").allSatisfy(t ->
                assertThat(t.endpoints()).noneSatisfy(e -> assertThat(e.path()).containsIgnoringCase("campaign")));
        assertThat(index.migrations()).isNotEmpty()
                .allSatisfy(m -> assertThat(m).doesNotContainIgnoringCase("campaign"));
    }

    @Test
    void theDeterministicRunRanksTheRealComponentsAndCompletesWithTwoApprovals() {
        engine = new OrchestrationEngine(new WorkflowGraph(WorkflowDefinitions.defaultSdlc()),
                AgentRegistry.of(DeterministicAgents.all(null)), PolicyEngine.withDefaultRules(), new InMemoryRunStore(),
                audit, NoopMetricsRecorder.INSTANCE,
                EngineConfig.builder().backoffScale(0).workspaceRoot(root).workspaceIndex(() -> index).build());
        ScenarioDefinition scenario = new ScenarioCatalog().require("brownfield");
        String id = engine.start(scenario.requirement(), scenario.key(), new RunOptions(scenario.autonomy(), null,
                scenario.faults(), null, scenario.workspace(), Map.of())).id();

        Run atDesign = awaitRun(id, r -> r.stage(WorkflowDefinitions.DESIGN).status() == StageStatus.AWAITING_APPROVAL);
        RequirementSpec spec = atDesign.acceptedPayload(RequirementSpec.class).orElseThrow();
        assertThat(spec.classification()).isEqualTo(RequirementSpec.Classification.BROWNFIELD);
        assertThat(spec.ambiguity().score()).as("not routed to clarification").isLessThan(0.5);
        assertThat(atDesign.clarification()).isNull();

        ImpactAnalysis impact = atDesign.acceptedPayload(ImpactAnalysis.class).orElseThrow();
        assertThat(impact.existingCodebase()).isTrue();
        List<ImpactedComponent> ranked = impact.components();
        assertThat(ranked.subList(0, 6)).extracting(ImpactedComponent::name).containsExactlyInAnyOrder(
                "LinkController", "BulkLinkController", "AnalyticsController", "LinkService", "BulkLinkService",
                "AnalyticsService");
        assertThat(ranked.subList(0, 6)).allSatisfy(c -> {
            assertThat(c.nature()).isEqualTo(ChangeNature.EXTEND);
            assertThat(c.confidence()).isEqualTo(1.0);
            assertThat(c.path()).startsWith("url-shortener/src/main/java/");
        });
        ImpactedComponent link = ranked.stream().filter(c -> c.name().equals("Link")).findFirst().orElseThrow();
        assertThat(link.type()).isEqualTo(ComponentType.ENTITY);
        assertThat(link.nature()).as("the label is stored with the link: a schema change").isEqualTo(ChangeNature.EXTEND);
        assertThat(link.confidence()).isGreaterThanOrEqualTo(0.9);
        long direct = ranked.stream().filter(c -> c.nature() != ChangeNature.REGRESSION_RISK).count();
        assertThat(ranked.indexOf(link)).as("the entity ranks among the directly impacted components")
                .isLessThan((int) direct);
        assertThat(impact.entities()).first().isEqualTo("Link (links)");
        // The analyzer reports the latest Flyway migration of the module that owns the impacted entities and proposes
        // the next version for that module (url-shortener V1 and a proposed V2, never orchestrator-app migrations).
        assertThat(impact.migrations()).hasSize(2);
        assertThat(impact.migrations().getFirst()).matches(".+/src/main/resources/db/migration/V\\d+__\\w+\\.sql");
        assertThat(impact.migrations().get(1)).matches("proposed: V\\d+__\\w+\\.sql");
        assertThat(impact.endpoints()).extracting(e -> e.method() + " " + e.path())
                .contains("POST /api/v1/links", "POST /api/v1/links/bulk", "GET /api/v1/links/{code}/analytics");

        Approval designGate = atDesign.stage(WorkflowDefinitions.DESIGN).pendingApproval();
        assertThat(designGate).isNotNull();
        assertThat(designGate.reason()).contains("GOV-001");
        DesignSpec design = atDesign.artifact(WorkflowDefinitions.DESIGN, designGate.artifactVersion()).orElseThrow()
                .payloadAs(DesignSpec.class);
        assertThat(design.dataModelChanges()).anySatisfy(d -> {
            assertThat(d.entity()).isEqualTo("Link");
            assertThat(d.migration()).matches("V\\d+__\\w+\\.sql");
            assertThat(d.backwardCompatible()).isTrue();
        });
        engine.approve(id, WorkflowDefinitions.DESIGN, Actor.human("alice"), "label column approved");

        Run atRelease = awaitRun(id, r -> r.stage(WorkflowDefinitions.RELEASE_READINESS).status()
                == StageStatus.AWAITING_APPROVAL);
        assertThat(atRelease.pendingApprovals()).singleElement()
                .satisfies(a -> assertThat(a.stageId()).isEqualTo(WorkflowDefinitions.RELEASE_READINESS));
        ChangeSet changeSet = atRelease.acceptedPayload(ChangeSet.class).orElseThrow();
        assertThat(changeSet.changes()).as("a new Flyway migration in the shortener's migration folder")
                .anySatisfy(c -> {
                    assertThat(c.path()).startsWith(MIGRATIONS).matches(".+/V\\d+__\\w+\\.sql");
                    assertThat(c.path()).isNotEqualTo(MIGRATIONS + "V1__init.sql");
                    assertThat(c.operation()).isEqualTo(ChangeSet.Operation.CREATE);
                });
        assertThat(changeSet.changes()).anySatisfy(c -> assertThat(c.path()).endsWith("api/LinkController.java"));
        assertThat(changeSet.changes()).anySatisfy(c -> assertThat(c.path()).endsWith("api/BulkLinkController.java"));
        engine.approve(id, WorkflowDefinitions.RELEASE_READINESS, Actor.human("bob"), "ship the labels");

        Run done = awaitRun(id, r -> r.status() == RunStatus.COMPLETED);
        assertThat(done.approvals()).extracting(Approval::stageId, Approval::status).containsExactly(
                tuple(WorkflowDefinitions.DESIGN, ApprovalStatus.APPROVED),
                tuple(WorkflowDefinitions.RELEASE_READINESS, ApprovalStatus.APPROVED));
        assertThat(done.acceptedPayload(ReleaseReadiness.class).orElseThrow().decision())
                .isNotEqualTo(ReleaseReadiness.GoNoGo.NO_GO);
        ChainVerification chain = AuditChain.verify(audit.forRun(id, 0));
        assertThat(chain.valid()).as(chain.summary()).isTrue();
    }

    private Run awaitRun(String id, Predicate<Run> condition) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20)).until(() -> {
            Run run = engine.get(id);
            assertThat(run.status()).as("run %s ended early: %s", id, run.haltDetail())
                    .isNotIn(RunStatus.ABORTED, RunStatus.SAFE_STOPPED);
            return condition.test(run);
        });
        return engine.get(id);
    }
}
