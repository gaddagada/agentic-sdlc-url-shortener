package com.agenticsdlc.orchestrator.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.agent.deterministic.ArchitectureAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.agent.deterministic.ImplementationAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.RequirementsAgent;
import com.agenticsdlc.orchestrator.core.agent.deterministic.SecurityReviewAgent;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.audit.ActorType;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuite.EvaluationCase;
import com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuite.Expectations;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

/**
 * Runs the bundled evaluation suite with the deterministic agents, and exercises the runner's control flow.
 */
class EvaluationSuiteRunnerTest {

    @TempDir
    static Path workspace;

    private static CodebaseIndex index;

    @BeforeAll
    static void indexWorkspace() {
        index = new CodebaseIndexer().index(SyntheticWorkspace.create(workspace.resolve("ws")));
    }

    /** Deterministic agents, in-memory ports, immediate retries; REPO cases reason over the synthetic workspace. */
    static final class DeterministicFactory implements EvaluationSuiteRunner.EngineFactory {
        final InMemoryAuditSink audit = new InMemoryAuditSink();

        @Override
        public OrchestrationEngine create(EvaluationCase evaluationCase) {
            EngineConfig config = EngineConfig.builder().backoffScale(0)
                    .workspaceIndex(() -> evaluationCase.workspace() == WorkspaceMode.REPO ? index : null).build();
            return new OrchestrationEngine(new WorkflowGraph(WorkflowDefinitions.defaultSdlc()),
                    AgentRegistry.of(DeterministicAgents.all(null)), PolicyEngine.withDefaultRules(),
                    new InMemoryRunStore(), audit, NoopMetricsRecorder.INSTANCE, config);
        }

        @Override
        public @Nullable CodebaseIndex index(EvaluationCase evaluationCase) {
            return evaluationCase.workspace() == WorkspaceMode.REPO ? index : null;
        }
    }

    private static String describe(SuiteResult result) {
        return result.markdown() + "\n" + result.cases().stream().map(c -> c.caseId() + ": " + c.observations()
                + (c.report() == null ? "" : "\n" + c.report().markdown())).collect(Collectors.joining("\n"));
    }

    @Test
    void theDeterministicAgentsPassTheBundledSuite() {
        EvaluationSuite suite = EvaluationSuite.loadDefault();
        assertThat(suite.cases()).hasSizeBetween(8, 10);
        DeterministicFactory factory = new DeterministicFactory();

        SuiteResult result = EvaluationSuiteRunner.run(factory, suite);

        assertThat(result.passed()).as(describe(result)).isTrue();
        assertThat(result.passedCount()).isEqualTo(suite.cases().size());
        assertThat(result.markdown()).contains("cases passed").contains("greenfield-guided");
        SuiteResult.CaseResult greenfield = result.cases().getFirst();
        assertThat(greenfield.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(greenfield.report().stageScores()).hasSize(10);
        assertThat(greenfield.observations()).anySatisfy(o -> assertThat(o).startsWith("approved the EXIT gate of "
                + "design"));
        List<AuditEvent> approvals = factory.audit.forRun(greenfield.runId(), 0).stream()
                .filter(e -> e.type() == AuditEventType.APPROVAL_GRANTED).toList();
        assertThat(approvals).isNotEmpty().allSatisfy(e -> {
            assertThat(e.actor().type()).isEqualTo(ActorType.HUMAN);
            assertThat(e.actor().id()).isEqualTo(EvaluationSuiteRunner.DEFAULT_APPROVER);
            assertThat(e.data()).containsEntry("comment", EvaluationSuiteRunner.APPROVAL_COMMENT);
        });
    }

    @Test
    void failedExpectationsAreReportedWithReasons() {
        EvaluationCase wrong = new EvaluationCase("wrong", "", "Build a URL shortener service: create short links "
                + "and redirect with HTTP 302.", null, null, AutonomyLevel.FULL_AUTO,
                new Expectations(true, null, true, false, 0.99, null));
        SuiteResult result = EvaluationSuiteRunner.run(new DeterministicFactory(),
                new EvaluationSuite("s", "1", List.of(wrong)));

        assertThat(result.passed()).isFalse();
        assertThat(result.cases().getFirst().failures()).anySatisfy(f -> assertThat(f).isEqualTo(
                "expected a clarification request")).anySatisfy(f -> assertThat(f).startsWith(
                "expected a policy denial")).anySatisfy(f -> assertThat(f).startsWith(
                "expected an overall score of at least 0.99"));
        assertThat(result.cases().getFirst().observations()).contains("stopped at the first human gate (approval)");
        assertThat(result.markdown()).contains("FAIL");
    }

    @Test
    void aNoGoReleaseIsNeverWaivedAndTimeoutsAndBrokenFactoriesFailTheCase() {
        EvaluationSuiteRunner.EngineFactory noGo = c -> {
            List<com.agenticsdlc.orchestrator.core.agent.StageAgent> agents = new java.util.ArrayList<>();
            for (AgentRole role : AgentRole.values()) {
                agents.add(role == AgentRole.RELEASE ? new FakeAgent(role, (ctx, call) -> StageResult.success(
                        Payloads.release(com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness.GoNoGo.NO_GO),
                        "no go")) : FakeAgent.succeeding(role));
            }
            return new OrchestrationEngine(new WorkflowGraph(WorkflowDefinitions.defaultSdlc()),
                    AgentRegistry.of(agents), PolicyEngine.withDefaultRules(), new InMemoryRunStore(),
                    new InMemoryAuditSink(), NoopMetricsRecorder.INSTANCE, EngineConfig.builder().backoffScale(0)
                    .build());
        };
        EvaluationCase complete = new EvaluationCase("no-go", "", "Create short links.", null, null,
                AutonomyLevel.FULL_AUTO, new Expectations(null, null, null, true, null, null));
        SuiteResult.CaseResult result = new EvaluationSuiteRunner().runCase(noGo, complete);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("expected the run to complete, it is WAITING_HUMAN");
        assertThat(result.observations()).contains("stopped at the NO_GO release gate; the runner never waives it");
        assertThat(result.status()).isEqualTo(RunStatus.WAITING_HUMAN);

        EvaluationSuiteRunner.EngineFactory slow = c -> {
            List<com.agenticsdlc.orchestrator.core.agent.StageAgent> agents = new java.util.ArrayList<>();
            for (AgentRole role : AgentRole.values()) {
                agents.add(role == AgentRole.PLANNING ? new FakeAgent(role, (ctx, call) -> {
                    Thread.sleep(2_000);
                    return StageResult.success(Payloads.taskPlan(), "slow");
                }) : FakeAgent.succeeding(role));
            }
            return new OrchestrationEngine(new WorkflowGraph(WorkflowDefinitions.defaultSdlc()),
                    AgentRegistry.of(agents), PolicyEngine.withDefaultRules(), new InMemoryRunStore(),
                    new InMemoryAuditSink(), NoopMetricsRecorder.INSTANCE, EngineConfig.builder().build());
        };
        SuiteResult.CaseResult timedOut = new EvaluationSuiteRunner("ops-bot", Duration.ofMillis(200))
                .runCase(slow, complete);
        assertThat(timedOut.failures()).anySatisfy(f -> assertThat(f).startsWith("timed out after PT0.2S"));

        SuiteResult.CaseResult broken = new EvaluationSuiteRunner().runCase(c -> {
            throw new IllegalStateException("no engine");
        }, complete);
        assertThat(broken.passed()).isFalse();
        assertThat(broken.runId()).isNull();
        assertThat(broken.failures()).singleElement().asString().contains("no engine");
    }

    @Test
    void loadsAndValidatesSuites() {
        EvaluationSuite suite = EvaluationSuite.fromJson("""
                {"name": "mini", "version": "1", "cases": [
                  {"id": "a", "requirement": "Create short links.", "expectations": {"expectCompleted": true}}]}""");
        EvaluationCase only = suite.cases().getFirst();
        assertThat(only.scenario()).isEqualTo("eval:a");
        assertThat(only.workspace()).isEqualTo(WorkspaceMode.NONE);
        assertThat(only.autonomy()).isEqualTo(AutonomyLevel.GUIDED);
        assertThat(only.expectations().drivesToCompletion()).isTrue();
        assertThat(EvaluationSuite.loadDefault().only(Set.of("greenfield-guided", "compliance-retention-no-go"))
                .cases())
                .extracting(EvaluationCase::id).containsExactly("greenfield-guided", "compliance-retention-no-go");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvaluationSuite.fromJson("{\"cases\": ["
                        + "{\"id\": \"a\", \"requirement\": \"x\"}, {\"id\": \"a\", \"requirement\": \"y\"}]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate evaluation case id a");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvaluationSuite.fromJson("{\"cases\": ["
                        + "{\"id\": \"a\", \"requirement\": \" \"}]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a requirement");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvaluationSuite.fromJson("{not json"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("invalid evaluation suite");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Expectations(null, null, null, null, 1.5, null))
                .hasMessageContaining("minOverallScore");
    }

    @Test
    void theComplianceCaseFailsTheSecurityReviewForTheDocumentedReason() {
        RequirementSpec spec = new RequirementsAgent().analyze(EvaluationSuite.loadDefault()
                .only(Set.of("compliance-retention-no-go")).cases().getFirst().requirement(), List.of(),
                java.util.Map.of(), null, WorkspaceMode.NONE, 0.5);
        DesignSpec design = new ArchitectureAgent().design(spec, new ImpactAnalyzer().proposeGreenfield(spec));
        SecurityReview review = new SecurityReviewAgent().review(design, new ImplementationAgent().implement(design,
                spec, null, null, List.of()));
        assertThat(review.checks()).filteredOn(c -> !c.passed()).extracting(Check::name)
                .containsExactly("ssrf-open-redirect-guard");
    }
}
