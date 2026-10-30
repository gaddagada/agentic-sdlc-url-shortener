package com.agenticsdlc.orchestrator.core.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class LlmStageAgentTest {

    /** A scripted client that records requests. */
    static final class FakeClient implements LlmClient {
        final List<LlmRequest> requests = new ArrayList<>();
        boolean available = true;
        Function<LlmRequest, Object> answer = r -> null;
        LlmException failure;

        @Override
        public String providerId() {
            return "anthropic";
        }

        @Override
        public String model() {
            return "claude-opus-5-5";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> LlmResponse<T> complete(LlmRequest request, Class<T> outputType) throws LlmException {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse<>((T) answer.apply(request), "{}", new LlmUsage(1200, 340), 25,
                    "claude-opus-5-5");
        }
    }

    private final FakeClient client = new FakeClient();
    private final JsonCodec codec = JsonCodec.standard();

    @Test
    void producesValidatedPayloadsWithUsageAndPromptHash() throws Exception {
        client.answer = r -> Payloads.design(RiskLevel.MEDIUM);
        StageAgent agent = LlmStageAgent.forRole(AgentRole.ARCHITECTURE, client, codec, 4000, Duration.ofSeconds(30));
        assertThat(agent.id()).isEqualTo("anthropic:claude-opus-5-5");
        StageContext context = Contexts.of(AgentRole.ARCHITECTURE, "Create short links.",
                Payloads.requirementSpec(RiskLevel.LOW), Payloads.impact());

        StageResult result = agent.execute(context);

        assertThat(result).isInstanceOfSatisfying(StageResult.Success.class, s -> {
            assertThat(s.payload()).isInstanceOf(DesignSpec.class);
            assertThat(s.llm()).isNotNull();
            assertThat(s.llm().providerId()).isEqualTo("anthropic");
            assertThat(s.llm().model()).isEqualTo("claude-opus-5-5");
            assertThat(s.llm().inputTokens()).isEqualTo(1200);
            assertThat(s.llm().outputTokens()).isEqualTo(340);
            assertThat(s.llm().promptHash()).hasSize(64);
        });
        LlmRequest request = client.requests.getFirst();
        assertThat(request.system()).contains(PromptTemplates.GOVERNANCE).contains("software architect");
        assertThat(request.user()).contains("## Requirement\nCreate short links.").contains("### intake (REQUIREMENT_SPEC v1)")
                .contains("\"normalizedStatement\"").contains("JSON schema").contains("\"alternativesConsidered\"");
        assertThat(request.maxOutputTokens()).isEqualTo(4000);
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void invalidOutputIsARetryableFailureCarryingUsage() {
        client.answer = r -> new DesignSpec("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, List.of(), null, List.of());
        StageAgent agent = LlmStageAgent.forRole(AgentRole.ARCHITECTURE, client, codec, 4000, Duration.ofSeconds(30));
        assertThatThrownBy(() -> agent.execute(Contexts.of(AgentRole.ARCHITECTURE, "x")))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).contains("overview must not be blank");
                    assertThat(f.trace()).isNotNull();
                    assertThat(f.trace().inputTokens()).isEqualTo(1200);
                });
        client.answer = r -> null;
        assertThatThrownBy(() -> agent.execute(Contexts.of(AgentRole.ARCHITECTURE, "x")))
                .isInstanceOfSatisfying(StageFailure.class, f -> assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT));
    }

    @Test
    void providerErrorsAreClassifiedByRetryability() {
        StageAgent agent = LlmStageAgent.forRole(AgentRole.IMPLEMENTATION, client, codec, 4000, Duration.ofSeconds(30));
        client.failure = new LlmException("rate limited", true);
        assertThatThrownBy(() -> agent.execute(Contexts.of(AgentRole.IMPLEMENTATION, "x")))
                .isInstanceOfSatisfying(StageFailure.class, f -> assertThat(f.kind()).isEqualTo(FailureKind.TRANSIENT));
        client.failure = new LlmException("provider refused", false);
        assertThatThrownBy(() -> agent.execute(Contexts.of(AgentRole.IMPLEMENTATION, "x")))
                .isInstanceOfSatisfying(StageFailure.class, f -> assertThat(f.kind()).isEqualTo(FailureKind.FATAL));
        client.available = false;
        assertThatThrownBy(() -> agent.execute(Contexts.of(AgentRole.IMPLEMENTATION, "x")))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.FATAL);
                    assertThat(f.getMessage()).contains("not available");
                });
    }

    @Test
    void requirementsRoleAppliesTheClarificationGate() throws Exception {
        RequirementSpec base = Payloads.requirementSpec(RiskLevel.LOW);
        RequirementSpec ambiguous = new RequirementSpec(base.normalizedStatement(), base.intent(),
                RequirementSpec.Classification.AMBIGUOUS, base.scopeIn(), base.scopeOut(), base.functional(),
                base.nonFunctional(), base.acceptanceCriteria(), base.assumptions(), base.openQuestions(),
                new RequirementSpec.AmbiguityReport(0.8, List.of(), List.of(new ClarificationQuestion("q1",
                        "Which target?", "why", List.of()))), base.preliminaryRisk());
        client.answer = r -> ambiguous;
        StageAgent agent = LlmStageAgent.forRole(AgentRole.REQUIREMENTS, client, codec, 4000, Duration.ofSeconds(30));
        assertThat(agent.execute(Contexts.of(AgentRole.REQUIREMENTS, "make it better")))
                .isInstanceOfSatisfying(StageResult.NeedsClarification.class, c -> {
                    assertThat(c.questions()).extracting(ClarificationQuestion::id).containsExactly("q1");
                    assertThat(c.llm()).isNotNull();
                });
    }

    @Test
    void releaseApprovalsFromTheModelAreDiscarded() throws Exception {
        ReleaseReadiness release = Payloads.release(ReleaseReadiness.GoNoGo.GO);
        ReleaseReadiness.ChangeRecord record = release.changeRecord();
        client.answer = r -> release.withChangeRecord(new ReleaseReadiness.ChangeRecord(record.summary(),
                record.riskLevel(), record.rollbackPlan(), List.of(new ReleaseReadiness.ApprovalRecord("release",
                "the-model", "2026-01-01T00:00:00Z", "self-approved")), record.artifactIds(), record.policyVerdict()));
        StageAgent agent = LlmStageAgent.forRole(AgentRole.RELEASE, client, codec, 4000, Duration.ofSeconds(30));
        StageResult.Success result = (StageResult.Success) agent.execute(Contexts.of(AgentRole.RELEASE, "x"));
        assertThat(((ReleaseReadiness) result.payload()).changeRecord().approvals()).isEmpty();
    }

    @Test
    void previousErrorsAndFeedbackAreIncludedInThePrompt() throws Exception {
        client.answer = r -> Payloads.forRole(AgentRole.IMPLEMENTATION);
        StageContext base = Contexts.of(AgentRole.IMPLEMENTATION, "x");
        StageContext retry = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), base.clarificationAnswers(), base.clarificationQuestions(), base.upstream(),
                List.of("[verify@v1] Add tests for: Feature"), 2, 1, "INVALID_OUTPUT: overview must not be blank",
                null, base.options(), List.of(), base.settings());
        StageResult result = LlmStageAgent.forRole(AgentRole.IMPLEMENTATION, client, codec, 4000,
                Duration.ofSeconds(30)).execute(retry);
        assertThat(result).isInstanceOf(StageResult.Success.class);
        assertThat(((StageResult.Success) result).payload()).isInstanceOf(ChangeSet.class);
        assertThat(client.requests.getFirst().user()).contains("## Feedback to address")
                .contains("Add tests for: Feature").contains("## The previous attempt failed")
                .contains("overview must not be blank");
        assertThat(client.requests.getFirst().system()).contains("contentKind FULL");
    }

    @Test
    void promptListsTheCodebaseIndexAndClarificationAnswersForCodebaseAwareRoles(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex index =
                new com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer()
                        .index(com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace.create(dir));
        client.answer = r -> Payloads.impact();
        StageContext base = Contexts.of(AgentRole.CODEBASE_ANALYSIS, "Add bulk creation",
                com.agenticsdlc.orchestrator.core.model.RunOptions.defaults(), index, List.of(),
                Payloads.requirementSpec(RiskLevel.LOW));
        StageContext context = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), java.util.Map.of("q1", "p95 under 50 ms"),
                List.of(new ClarificationQuestion("q1", "Which latency target?", "why", List.of())), base.upstream(),
                List.of(), 1, 1, null, index, base.options(), List.of(), base.settings());
        LlmStageAgent.forRole(AgentRole.CODEBASE_ANALYSIS, client, codec, 4000, Duration.ofSeconds(30))
                .execute(context);
        String user = client.requests.getFirst().user();
        assertThat(user).contains("## Clarification answers (authoritative)").contains("Answer: p95 under 50 ms")
                .contains("## Codebase index").contains("api/LinkController.java [CONTROLLER] endpoints: POST "
                        + "/api/v1/links").contains("migrations: url-shortener/src/main/resources/db/migration/V1__init.sql");
        client.requests.clear();
        LlmStageAgent.forRole(AgentRole.DOCUMENTATION, client, codec, 4000, Duration.ofSeconds(30)).id();
        assertThat(PromptTemplates.system(AgentRole.RELEASE)).contains("Leave changeRecord.approvals empty");
    }

    @Test
    void factoryCreatesOneAgentPerRoleAndChecksPayloadTypes() {
        assertThat(LlmStageAgent.forAllRoles(client, codec)).extracting(StageAgent::role)
                .containsExactly(AgentRole.values());
        assertThatThrownBy(() -> new LlmStageAgent<>(AgentRole.PLANNING, DesignSpec.class, client, codec, 10,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
