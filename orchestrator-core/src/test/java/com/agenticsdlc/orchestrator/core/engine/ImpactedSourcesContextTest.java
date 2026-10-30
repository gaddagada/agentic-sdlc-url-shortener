package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;
import com.agenticsdlc.orchestrator.core.testsupport.FakeAgent;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources;

/**
 * The implementation, verification and security review stages of REPO runs see the bounded source of the impacted
 * files; other stages and runs without the repository workspace do not.
 */
class ImpactedSourcesContextTest {

    private static final String REQUIREMENT = "Add bulk creation of short links to the existing URL shortener.";
    private static final String BASE = "url-shortener/src/main/java/com/agenticsdlc/shortener/";
    private static final String LINK_SERVICE = BASE + "service/LinkService.java";
    private static final String CONTROLLER = BASE + "api/LinkController.java";
    private static final String VALIDATOR = BASE + "service/UrlValidator.java";
    private static final String GENERATOR = BASE + "service/ShortCodeGenerator.java";
    private static final String REDIRECT = BASE + "api/RedirectController.java";
    private static final String RATE_LIMITER = BASE + "config/TokenBucketRateLimiter.java";
    private static final String PROPERTIES = BASE + "config/ShortenerProperties.java";

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    private static ImpactAnalysis impact() {
        return new ImpactAnalysis("ws", 20, true, List.of(
                new ImpactedComponent("LinkService", ComponentType.SERVICE, LINK_SERVICE, 0.9, List.of("create"),
                        ChangeNature.MODIFY),
                new ImpactedComponent("LinkController", ComponentType.CONTROLLER, CONTROLLER, 0.8, List.of("api"),
                        ChangeNature.EXTEND),
                new ImpactedComponent("UrlValidator", ComponentType.SERVICE, VALIDATOR, 0.5, List.of("validation"),
                        ChangeNature.REGRESSION_RISK),
                new ImpactedComponent("Outside", ComponentType.OTHER, "../outside.txt", 0.5, List.of("escape"),
                        ChangeNature.MODIFY),
                // a path without the module prefix and a component without a path are resolved through the index
                new ImpactedComponent("TokenBucketRateLimiter", ComponentType.OTHER,
                        "shortener/config/TokenBucketRateLimiter.java", 0.6, List.of("limits"), ChangeNature.MODIFY),
                new ImpactedComponent("ShortenerProperties", ComponentType.OTHER, "", 0.4, List.of("settings"),
                        ChangeNature.EXTEND),
                new ImpactedComponent("BulkLinkService", ComponentType.SERVICE, BASE + "service/BulkLinkService.java",
                        0.9, List.of("new"), ChangeNature.NEW)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "bulk creation");
    }

    private static ChangeSet modifyingTheGenerator() {
        return new ChangeSet("HEAD", List.of(
                new ChangeSet.FileChange(GENERATOR, ChangeSet.Operation.MODIFY, "java", "longer codes",
                        ChangeSet.ContentKind.FULL, "class ShortCodeGenerator {}", List.of("T1")),
                new ChangeSet.FileChange(BASE.replace("src/main", "src/test") + "service/ShortCodeGeneratorTest.java",
                        ChangeSet.Operation.CREATE, "java", "test", ChangeSet.ContentKind.FULL,
                        "class ShortCodeGeneratorTest { void t() { org.junit.jupiter.api.Assertions.fail(\"x\"); } }",
                        List.of("T1"))), "./mvnw verify", List.of(), "revert", List.of("T1"));
    }

    private static VerificationReport failedNamingTheRedirectController() {
        return new VerificationReport(List.of(Check.fail("diff-applies", "hunk does not match")),
                new VerificationReport.Traceability(1, 1, List.of()), null, false,
                List.of("Rewrite the hunk of " + REDIRECT + " against its source"));
    }

    @Test
    void implementationVerificationAndSecurityReviewSeeBoundedImpactedSources(@TempDir Path dir) throws Exception {
        Path ws = SyntheticWorkspace.create(dir.resolve("ws"));
        Files.writeString(dir.resolve("outside.txt"), "outside the workspace");
        CodebaseIndex index = new CodebaseIndexer().index(ws);
        FakeAgent design = FakeAgent.succeeding(AgentRole.ARCHITECTURE);
        FakeAgent implement = new FakeAgent(AgentRole.IMPLEMENTATION, (ctx, call) ->
                StageResult.success(modifyingTheGenerator(), "implemented " + call));
        FakeAgent verify = new FakeAgent(AgentRole.VERIFICATION, (ctx, call) -> StageResult.success(call == 1
                ? failedNamingTheRedirectController() : Payloads.verification(true), "verified " + call));
        FakeAgent docs = FakeAgent.succeeding(AgentRole.DOCUMENTATION);
        FakeAgent security = FakeAgent.succeeding(AgentRole.SECURITY_REVIEW);
        harness.agent(new FakeAgent(AgentRole.CODEBASE_ANALYSIS, (ctx, call) -> StageResult.success(impact(), "ok")))
                .agent(design).agent(implement).agent(verify).agent(docs).agent(security)
                .config(b -> b.workspaceRoot(ws).workspaceIndex(() -> index).sourceContextPerFileChars(700));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "sources", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)
                .withWorkspace(WorkspaceMode.REPO)).id();

        harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);

        String linkService = Files.readString(ws.resolve(LINK_SERVICE));
        assertThat(linkService.length()).isGreaterThan(700);
        StageContext first = implement.contexts().getFirst();
        assertThat(first.impactedSources()).containsOnlyKeys(LINK_SERVICE, RATE_LIMITER, CONTROLLER, PROPERTIES,
                VALIDATOR);
        assertThat(first.impactedSources().get(LINK_SERVICE)).hasSizeLessThanOrEqualTo(700)
                .startsWith("package com.agenticsdlc.shortener.service;").contains(ImpactedSources.TRUNCATED_MARKER);
        assertThat(first.impactedSources().get(VALIDATOR)).isEqualTo(Files.readString(ws.resolve(VALIDATOR)));
        assertThat(first.impactedSources().get(RATE_LIMITER)).contains("class TokenBucketRateLimiter");
        assertThat(first.impactedSources().values()).noneMatch(s -> s.contains("outside the workspace"));
        assertThat(first.impactedSourceNotes()).contains("../outside.txt: refused (outside the workspace root)",
                LINK_SERVICE + ": truncated to 700 of " + linkService.length() + " characters (per-file limit of 700 "
                        + "characters)");

        StageContext second = implement.contexts().get(1);
        assertThat(second.feedback()).singleElement().asString().contains(REDIRECT);
        assertThat(second.impactedSources()).as("the accepted change set's MODIFY targets and the files named by "
                + "the verification feedback").containsKeys(GENERATOR, REDIRECT, LINK_SERVICE);
        assertThat(second.impactedSources().get(GENERATOR)).contains("public String next()");

        assertThat(verify.contexts().getFirst().impactedSources()).containsKeys(GENERATOR, LINK_SERVICE, CONTROLLER);
        assertThat(security.contexts().getFirst().impactedSources()).containsKeys(GENERATOR, LINK_SERVICE);
        assertThat(design.contexts().getFirst().impactedSources()).isEmpty();
        assertThat(design.contexts().getFirst().impactedSourceNotes()).isEmpty();
        assertThat(docs.contexts().getFirst().hasImpactedSources()).isFalse();
    }

    @Test
    void runsWithoutTheRepositoryWorkspaceOrWithoutAWorkspaceRootGetNoSources(@TempDir Path dir) {
        Path ws = SyntheticWorkspace.create(dir.resolve("ws"));
        FakeAgent implement = FakeAgent.succeeding(AgentRole.IMPLEMENTATION);
        harness.agent(new FakeAgent(AgentRole.CODEBASE_ANALYSIS, (ctx, call) -> StageResult.success(impact(), "ok")))
                .agent(implement).config(b -> b.workspaceRoot(ws));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(REQUIREMENT, "no-repo", RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO))
                .id();
        harness.awaitApproval(id, WorkflowDefinitions.RELEASE_READINESS);
        assertThat(implement.contexts().getFirst().hasImpactedSources()).isFalse();

        StageContext plain = implement.contexts().getFirst();
        assertThat(plain.withImpactedSources(java.util.Map.of("b.txt", "B", "a.txt", "A"), List.of("note"))
                .impactedSources().keySet()).containsExactly("a.txt", "b.txt");
    }
}
