package com.agenticsdlc.orchestrator.core.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
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
        assertThat(request.system()).contains(PromptTemplates.GOVERNANCE).contains(PromptTemplates.ENUM_VALUES)
                .contains("software architect");
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
    void lowerCaseEnumValuesInModelOutputAreAcceptedAndUnknownValuesAreInvalidOutput() throws Exception {
        RequirementSpec base = Payloads.requirementSpec(RiskLevel.LOW);
        RequirementSpec withNfr = new RequirementSpec(base.normalizedStatement(), base.intent(), base.classification(),
                base.scopeIn(), base.scopeOut(), base.functional(), List.of(new RequirementSpec.Requirement("NFR-1",
                "p95 redirect latency under 50 ms", RequirementSpec.Priority.MUST, RequirementSpec.Category.PERFORMANCE,
                true)), base.acceptanceCriteria(), base.assumptions(), base.openQuestions(), base.ambiguity(),
                base.preliminaryRisk());
        // what a model returned in a live call: correct structure, lower-case enum constants
        String output = codec.toJson(withNfr).replace("\"category\":\"PERFORMANCE\"", "\"category\":\"performance\"")
                .replace("\"category\":\"FUNCTIONAL\"", "\"category\":\"functional\"")
                .replace("\"classification\":\"GREENFIELD\"", "\"classification\":\"Greenfield\"");
        assertThat(output).contains("\"category\":\"performance\"").contains("\"category\":\"functional\"");
        // the Anthropic and Ollama clients parse the model text with the shared codec
        client.answer = r -> codec.mapper().treeToValue(codec.readTree(output), RequirementSpec.class);
        StageAgent agent = LlmStageAgent.forRole(AgentRole.REQUIREMENTS, client, codec, 4000, Duration.ofSeconds(30));

        StageResult result = agent.execute(Contexts.of(AgentRole.REQUIREMENTS, "Create short links."));

        assertThat(result).isInstanceOfSatisfying(StageResult.Success.class, s -> {
            RequirementSpec spec = (RequirementSpec) s.payload();
            assertThat(spec.nonFunctional()).singleElement().satisfies(r ->
                    assertThat(r.category()).isEqualTo(RequirementSpec.Category.PERFORMANCE));
            assertThat(spec.functional().getFirst().category()).isEqualTo(RequirementSpec.Category.FUNCTIONAL);
            assertThat(spec.classification()).isEqualTo(RequirementSpec.Classification.GREENFIELD);
            assertThat(spec).isEqualTo(withNfr);
        });

        client.answer = r -> codec.mapper().treeToValue(codec.readTree(output.replace("\"performance\"",
                "\"latency\"")), RequirementSpec.class);
        assertThatThrownBy(() -> agent.execute(Contexts.of(AgentRole.REQUIREMENTS, "Create short links.")))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).startsWith("output could not be parsed").contains("latency");
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
        assertThat(client.requests.getFirst().system()).contains("contentKind FULL only for new files")
                .contains("MODIFY with contentKind DIFF").contains("never repeat unchanged code")
                .contains("never CREATE an existing file").contains("start with url-shortener/ or docs/")
                .contains(PromptTemplates.OUTPUT_SIZE);
    }

    // ------------------------------------------------------------------ context policy

    private static final String CHANGE_SET_MARKER = "UNIQUE_CHANGE_SET_BODY_MARKER";

    /** The nine upstream payloads of release-readiness in topological order; the change set has a large body. */
    private static ArtifactPayload[] pipelinePayloads() {
        String body = "class Feature {\n" + ("    // " + CHANGE_SET_MARKER + " filler line of generated code\n")
                .repeat(600) + "}\n";
        return new ArtifactPayload[] {Payloads.requirementSpec(RiskLevel.LOW), Payloads.taskPlan(), Payloads.impact(),
                Payloads.design(RiskLevel.MEDIUM), Payloads.changeSet("url-shortener/src/main/java/com/example/"
                + "Feature.java", body), Payloads.testPlan(), Payloads.verification(true), Payloads.docs(),
                Payloads.security()};
    }

    private static StageContext withDirectPredecessors(StageContext base, Set<String> direct) {
        return new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), base.clarificationAnswers(), base.clarificationQuestions(), base.upstream(),
                base.feedback(), base.attempt(), base.planVersion(), base.previousError(), base.workspaceIndex(),
                base.options(), base.approvals(), base.settings(), direct);
    }

    private String promptFor(AgentRole role, Object answer, StageContext context) throws Exception {
        client.requests.clear();
        client.answer = r -> answer;
        LlmStageAgent.forRole(role, client, codec, 4000, Duration.ofSeconds(30)).execute(context);
        return client.requests.getFirst().user();
    }

    private static String full(String stage, String kind) {
        return "### " + stage + " (" + kind + " v1)\n```json\n";
    }

    private static String summary(String stage, String kind) {
        return "### " + stage + " (" + kind + " v1, summary)\n";
    }

    @Test
    void designPromptEmbedsItsDirectPredecessorsAndTheRequirementSpecInFull() throws Exception {
        ArtifactPayload[] all = pipelinePayloads();
        StageContext base = Contexts.of(AgentRole.ARCHITECTURE, "Add bulk creation", all[0], all[1], all[2]);
        StageContext context = withDirectPredecessors(base, Set.of("decompose", "impact"));
        assertThat(context.isDirectPredecessor("impact")).isTrue();
        assertThat(context.isDirectPredecessor("intake")).isFalse();

        String user = promptFor(AgentRole.ARCHITECTURE, Payloads.design(RiskLevel.MEDIUM), context);

        assertThat(user).contains(PromptTemplates.FULL_ARTIFACTS_HEADING)
                .contains(full("intake", "REQUIREMENT_SPEC")).contains("\"normalizedStatement\"")
                .contains(full("decompose", "TASK_PLAN")).contains("\"criticalPath\"")
                .contains(full("impact", "IMPACT_ANALYSIS")).contains("\"existingCodebase\"")
                .doesNotContain(PromptTemplates.SUMMARIZED_ARTIFACTS_HEADING).doesNotContain(", summary)");
    }

    @Test
    void releasePromptSummarizesIndirectArtifactsInsteadOfEmbeddingTheirJson() throws Exception {
        ArtifactPayload[] all = pipelinePayloads();
        StageContext base = Contexts.of(AgentRole.RELEASE, "Add bulk creation", all);
        assertThat(base.upstream().keySet()).containsExactly("intake", "decompose", "impact", "design", "implement",
                "test-design", "verify", "document", "security-scan");
        StageContext context = withDirectPredecessors(base, Set.of("verify", "document", "security-scan"));

        String user = promptFor(AgentRole.RELEASE, Payloads.release(ReleaseReadiness.GoNoGo.GO), context);

        assertThat(user).contains(full("verify", "VERIFICATION_REPORT")).contains("\"structuralChecks\"")
                .contains(full("document", "DOCUMENTATION_BUNDLE")).contains("\"operationsRunbook\"")
                .contains(full("security-scan", "SECURITY_REVIEW")).contains("\"threats\"")
                .contains(full("intake", "REQUIREMENT_SPEC")).contains("\"acceptanceCriteria\"");
        assertThat(user).contains(PromptTemplates.SUMMARIZED_ARTIFACTS_HEADING)
                .contains(summary("decompose", "TASK_PLAN")).contains(summary("impact", "IMPACT_ANALYSIS"))
                .contains(summary("design", "DESIGN_SPEC")).contains(summary("implement", "CHANGE_SET"))
                .contains(summary("test-design", "TEST_PLAN"));
        for (String indirect : List.of("decompose (TASK_PLAN", "impact (IMPACT_ANALYSIS", "design (DESIGN_SPEC",
                "implement (CHANGE_SET", "test-design (TEST_PLAN")) {
            assertThat(user).as(indirect).doesNotContain("### " + indirect + " v1)\n```json");
        }
        // the change set appears as a file list without its content and without its JSON
        assertThat(user).contains("url-shortener/src/main/java/com/example/Feature.java")
                .doesNotContain(CHANGE_SET_MARKER).doesNotContain("\"contentKind\"").doesNotContain("\"criticalPath\"")
                .doesNotContain("\"alternativesConsidered\"");
        assertThat(user.indexOf(PromptTemplates.SUMMARIZED_ARTIFACTS_HEADING))
                .isGreaterThan(user.indexOf(PromptTemplates.FULL_ARTIFACTS_HEADING));

        // materially smaller than embedding every transitive artifact in full (the previous behaviour)
        String everything = promptFor(AgentRole.RELEASE, Payloads.release(ReleaseReadiness.GoNoGo.GO), base);
        assertThat(everything).contains(CHANGE_SET_MARKER).contains(full("implement", "CHANGE_SET"))
                .doesNotContain(PromptTemplates.SUMMARIZED_ARTIFACTS_HEADING);
        assertThat(user.length()).isLessThan(everything.length() / 4);
    }

    @Test
    void summariesAreBoundedForLargeIndirectArtifacts() throws Exception {
        RequirementSpec spec = Payloads.requirementSpec(RiskLevel.LOW);
        DesignSpec base = Payloads.design(RiskLevel.MEDIUM);
        List<DesignSpec.Component> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new DesignSpec.Component("Component" + i, "responsibility number " + i, "SERVICE", true));
        }
        DesignSpec large = new DesignSpec(base.overview(), many, base.apiChanges(), base.dataModelChanges(),
                base.sequenceFlows(), base.nfrStrategies(), base.securityConsiderations(), base.dataHandling(),
                base.alternativesConsidered(), base.risk(), base.decisions());
        StageContext context = withDirectPredecessors(Contexts.of(AgentRole.TEST_DESIGN, "x", spec, large,
                Payloads.changeSet("url-shortener/src/main/java/A.java", "class A {}")), Set.of("implement"));
        String user = promptFor(AgentRole.TEST_DESIGN, Payloads.testPlan(), context);
        int start = user.indexOf(summary("design", "DESIGN_SPEC"));
        assertThat(start).isPositive();
        String designSection = user.substring(start, user.indexOf("\n```\n", start));
        assertThat(designSection).contains("Component0").doesNotContain("Component199").endsWith("[truncated]");
        assertThat(designSection.length()).isLessThan(PromptTemplates.SUMMARY_CHARS + 200);
        assertThat(PromptTemplates.system(AgentRole.TEST_DESIGN))
                .contains("coverageMatrix values must be comma-separated test case ids only")
                .contains("\"TC-1, TC-4\"").contains("no prose");
    }

    @Test
    void aChangeSetTheSandboxRejectsIsInvalidOutputThatStillReportsTokens(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path workspace = com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace
                .create(dir.resolve("ws"));
        String existing = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java";
        client.answer = r -> new ChangeSet("HEAD", List.of(new ChangeSet.FileChange(existing,
                ChangeSet.Operation.CREATE, "java", "rewrite", ChangeSet.ContentKind.FULL, "class LinkController {}",
                List.of())), "./mvnw verify", List.of(), "revert", List.of());
        StageContext base = Contexts.of(AgentRole.IMPLEMENTATION, "x");
        StageContext context = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), java.util.Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null,
                null, com.agenticsdlc.orchestrator.core.model.RunOptions.defaults()
                .withWorkspace(com.agenticsdlc.orchestrator.core.model.WorkspaceMode.REPO), List.of(),
                new com.agenticsdlc.orchestrator.core.agent.AgentSettings(0.5, true, false, workspace,
                        dir.resolve("sandboxes"), Duration.ofMinutes(1)));
        assertThatThrownBy(() -> LlmStageAgent.forRole(AgentRole.IMPLEMENTATION, client, codec, 4000,
                Duration.ofSeconds(30)).execute(context)).isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.getMessage()).contains("CREATE of existing file " + existing);
                    assertThat(f.trace()).isNotNull();
                    assertThat(f.trace().inputTokens()).isEqualTo(1200);
                    assertThat(f.trace().outputTokens()).isEqualTo(340);
                });
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
