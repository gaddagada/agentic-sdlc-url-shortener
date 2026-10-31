package com.agenticsdlc.orchestrator.core.agent.llm;

import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.APPLICATION_YML;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.BASE;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.CREATE_REQUEST;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.LINK;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.LINK_SERVICE;
import static com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture.MIGRATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.CampaignFixture;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

/**
 * Model-written impact analyses and designs of REPO runs are reconciled with the indexed tree and the static impact
 * analysis, so the components later stages rely on carry real paths; the prompts give the models those paths.
 */
class IndexReconciliationTest {

    private static final RunOptions REPO = RunOptions.defaults().withWorkspace(WorkspaceMode.REPO);

    @TempDir
    static Path dir;

    private static Path workspace;
    private static CodebaseIndex index;
    private static RequirementSpec spec;
    private static ImpactAnalysis staticImpact;

    private final LlmStageAgentTest.FakeClient client = new LlmStageAgentTest.FakeClient();
    private final JsonCodec codec = JsonCodec.standard();

    @BeforeAll
    static void analyse() {
        workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        index = new CodebaseIndexer().index(workspace);
        spec = CampaignFixture.spec();
        staticImpact = new ImpactAnalyzer().analyze(index, spec);
    }

    private static ImpactedComponent component(ImpactAnalysis analysis, String path) {
        return analysis.components().stream().filter(c -> c.path().equals(path)).findFirst().orElseThrow();
    }

    private StageResult run(AgentRole role, StageContext context) throws Exception {
        return LlmStageAgent.forRole(role, client, codec, 4000, Duration.ofSeconds(30)).execute(context);
    }

    @Test
    void theStaticAnalysisRanksTheJavaTypesTheModelOmitted() {
        assertThat(component(staticImpact, LINK).nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(component(staticImpact, LINK).confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(component(staticImpact, LINK_SERVICE).confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(staticImpact.entities()).contains("Link (links)");
        assertThat(staticImpact.endpoints()).isNotEmpty();
    }

    @Test
    void aResourcesOnlyImpactIsReconciledWithTheIndexAndTheStaticAnalysis() throws Exception {
        client.answer = r -> CampaignFixture.resourcesOnlyImpact();
        StageContext context = Contexts.of(AgentRole.CODEBASE_ANALYSIS, CampaignFixture.REQUIREMENT, REPO, index,
                List.of(), spec).withStaticImpact(staticImpact);

        StageResult result = run(AgentRole.CODEBASE_ANALYSIS, context);

        ImpactAnalysis analysis = (ImpactAnalysis) ((StageResult.Success) result).payload();
        ImpactedComponent link = component(analysis, LINK);
        assertThat(link.name()).isEqualTo("Link");
        assertThat(link.type()).isEqualTo(ComponentType.ENTITY);
        assertThat(link.nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(link.reasons()).contains("added from static analysis: the model omitted this component");
        assertThat(component(analysis, LINK_SERVICE).nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(analysis.components()).as("never silently dropped: every confident static candidate is merged")
                .extracting(ImpactedComponent::path).containsAll(staticImpact.components().stream()
                        .filter(c -> c.confidence() >= 0.8 && c.nature() == ChangeNature.EXTEND)
                        .map(ImpactedComponent::path).toList());
        assertThat(component(analysis, MIGRATIONS + "V3__add_campaign_label.sql").nature())
                .as("a missing change type of a file the index does not list").isEqualTo(ChangeNature.NEW);
        ImpactedComponent init = component(analysis, MIGRATIONS + "V1__init.sql");
        assertThat(init.nature()).isEqualTo(ChangeNature.MODIFY);
        assertThat(init.type()).isEqualTo(ComponentType.RESOURCE);
        assertThat(component(analysis, APPLICATION_YML).nature()).isEqualTo(ChangeNature.MODIFY);
        assertThat(analysis.entities()).isEqualTo(staticImpact.entities());
        assertThat(analysis.endpoints()).isEqualTo(staticImpact.endpoints());
        assertThat(analysis.migrations()).isEqualTo(staticImpact.migrations());
        assertThat(analysis.indexedFiles()).isEqualTo(index.indexedFiles());
        assertThat(analysis.reconciliation()).contains("merged from static analysis: Link (ENTITY, EXTEND, "
                        + "confidence " + String.format(java.util.Locale.ROOT, "%.2f", link.confidence()) + ", " + LINK
                        + ")")
                .anySatisfy(n -> assertThat(n).startsWith("merged from static analysis: LinkService (SERVICE"))
                .anySatisfy(n -> assertThat(n).startsWith("entities back-filled from static analysis: Link (links)"))
                .anySatisfy(n -> assertThat(n).startsWith("endpoints back-filled from static analysis: "))
                .anySatisfy(n -> assertThat(n).startsWith("component 'V1 init migration' had no type; set to "
                        + "RESOURCE"));
        assertThat(analysis.markdown()).contains("### Reconciliation with static analysis");

        LlmRequest request = client.requests.getFirst();
        assertThat(request.user()).contains(PromptTemplates.STATIC_IMPACT_HEADING)
                .contains("keep them unless you can say why they are not affected")
                .contains("- " + LINK + " | Link | ENTITY | EXTEND | ")
                .contains("- " + LINK_SERVICE + " | LinkService | SERVICE | EXTEND | ")
                .contains("Entities: Link (links)").contains("Endpoints: ")
                .contains("May be empty only for a NEW component");
        assertThat(request.system()).contains(PromptTemplates.INDEX_PATHS_RULE)
                .contains("keep them unless you can say why they are not affected").contains("Leave reconciliation");
        assertThat(request.stableContext()).doesNotContain(PromptTemplates.STATIC_IMPACT_HEADING);
    }

    @Test
    void componentsWithProseNamesOrWrongPackagesResolveAndModelNotesAreReplaced() throws Exception {
        client.answer = r -> new ImpactAnalysis("none", 0, false, List.of(
                new ImpactedComponent("Link entity", ComponentType.ENTITY, "", 0.9, List.of("label column"),
                        ChangeNature.EXTEND),
                new ImpactedComponent("CreateLinkRequest", ComponentType.OTHER, BASE + "link/dto/CreateLinkRequest.java",
                        0.8, List.of("new field"), ChangeNature.MODIFY),
                new ImpactedComponent("Link", ComponentType.ENTITY, LINK, 0.7, List.of("again"), ChangeNature.MODIFY),
                new ImpactedComponent("CampaignStatsService", ComponentType.SERVICE,
                        BASE + "service/CampaignStatsService.java", 0.8, List.of("new"), ChangeNature.NEW),
                new ImpactedComponent("Ghost", ComponentType.OTHER, BASE + "ghost/Ghost.java", 0.4, List.of("?"),
                        ChangeNature.MODIFY)),
                List.of(), List.of("Link"), List.of(), List.of(), List.of(), List.of(), "labels",
                List.of("model notes are not trusted"));
        StageContext context = Contexts.of(AgentRole.CODEBASE_ANALYSIS, CampaignFixture.REQUIREMENT, REPO, index,
                List.of(), spec).withStaticImpact(staticImpact);

        ImpactAnalysis analysis = (ImpactAnalysis) ((StageResult.Success) run(AgentRole.CODEBASE_ANALYSIS, context))
                .payload();

        assertThat(analysis.existingCodebase()).isTrue();
        assertThat(analysis.workspaceRoot()).isEqualTo(index.rootName());
        assertThat(component(analysis, LINK).name()).isEqualTo("Link");
        assertThat(component(analysis, LINK).reasons()).containsExactly("label column");
        assertThat(analysis.components()).filteredOn(c -> c.path().equals(LINK)).as("one component per file")
                .hasSize(1);
        assertThat(component(analysis, CREATE_REQUEST).nature()).isEqualTo(ChangeNature.MODIFY);
        assertThat(component(analysis, BASE + "service/CampaignStatsService.java").nature())
                .isEqualTo(ChangeNature.NEW);
        assertThat(component(analysis, BASE + "ghost/Ghost.java").nature()).isEqualTo(ChangeNature.MODIFY);
        assertThat(analysis.entities()).as("not back-filled when the model named entities").containsExactly("Link");
        assertThat(analysis.reconciliation()).doesNotContain("model notes are not trusted")
                .contains("resolved component 'Link entity' (no path) to " + LINK + " (Link)",
                        "resolved component 'CreateLinkRequest' (path " + BASE + "link/dto/CreateLinkRequest.java) to "
                                + CREATE_REQUEST,
                        "dropped duplicate component 'Link' for " + LINK,
                        "component 'Ghost': path " + BASE + "ghost/Ghost.java is not in the codebase index and could "
                                + "not be resolved to an indexed file",
                        "existingCodebase set to true: the workspace index lists " + index.indexedFiles() + " files");
    }

    @Test
    void withoutAnIndexNothingIsReconciledAndModelNotesAreDiscarded() throws Exception {
        ImpactAnalysis greenfield = new ImpactAnalysis("none", 0, false, List.of(new ImpactedComponent(
                "LinkController", ComponentType.CONTROLLER, BASE + "api/LinkController.java", 0.8, List.of("new"),
                ChangeNature.NEW)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "greenfield",
                List.of("model notes"));
        client.answer = r -> greenfield;

        ImpactAnalysis analysis = (ImpactAnalysis) ((StageResult.Success) run(AgentRole.CODEBASE_ANALYSIS,
                Contexts.of(AgentRole.CODEBASE_ANALYSIS, "Create short links.", spec))).payload();

        assertThat(analysis.reconciliation()).isEmpty();
        assertThat(analysis.components()).isEqualTo(greenfield.components());
        assertThat(analysis.existingCodebase()).isFalse();
        assertThat(client.requests.getFirst().user()).doesNotContain(PromptTemplates.STATIC_IMPACT_HEADING);
    }

    @Test
    void designComponentsNamedInProseGetTheirIndexedPaths() throws Exception {
        client.answer = r -> new DesignSpec("Campaign labels", List.of(
                new DesignSpec.Component("Link entity", "stores the label", "ENTITY", false),
                new DesignSpec.Component("CreateLink request/response DTOs and bulk item DTO", "carry the label",
                        "OTHER", false),
                new DesignSpec.Component("Link service (single and bulk create)", "persists the label", "SERVICE",
                        false),
                new DesignSpec.Component("Link", "entity", "ENTITY", false, BASE + "link/Link.java"),
                new DesignSpec.Component("Campaign analytics endpoint", "reports clicks", "CONTROLLER", true),
                new DesignSpec.Component("Ghost helper", "does not exist", "OTHER", false)),
                List.of(), List.of(), List.of(), List.of(), List.of("validate the label"),
                new DesignSpec.DataHandling(List.of(), "n/a", "n/a"),
                List.of(new DesignSpec.Alternative("Column", List.of(), List.of(), true),
                        new DesignSpec.Alternative("Table", List.of(), List.of(), false)),
                new com.agenticsdlc.orchestrator.core.artifact.RiskAssessment(RiskLevel.MEDIUM, List.of()),
                List.of(new DesignSpec.Decision("Label", "column", "simple")));
        ImpactAnalysis accepted = new ImpactAnalysis("ws", index.indexedFiles(), true, List.of(
                new ImpactedComponent("Link entity", ComponentType.ENTITY, "", 0.9, List.of("label"),
                        ChangeNature.EXTEND),
                new ImpactedComponent("V3 migration", ComponentType.RESOURCE, MIGRATIONS + "V3__campaign.sql", 0.9,
                        List.of("column"), ChangeNature.NEW)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "labels");
        StageContext context = Contexts.of(AgentRole.ARCHITECTURE, CampaignFixture.REQUIREMENT, REPO, index,
                List.of(), spec, accepted).withStaticImpact(staticImpact);

        DesignSpec design = (DesignSpec) ((StageResult.Success) run(AgentRole.ARCHITECTURE, context)).payload();

        assertThat(design.components()).extracting(DesignSpec.Component::path).containsExactly(LINK, CREATE_REQUEST,
                LINK_SERVICE, LINK, "", "");
        assertThat(design.components()).extracting(DesignSpec.Component::name).as("design names are kept")
                .startsWith("Link entity", "CreateLink request/response DTOs and bulk item DTO");
        String user = client.requests.getFirst().user();
        int start = user.indexOf(PromptTemplates.IMPACTED_PATHS_HEADING);
        assertThat(start).as("after the upstream artifacts").isGreaterThan(user.indexOf(
                PromptTemplates.FULL_ARTIFACTS_HEADING));
        String section = user.substring(start, user.indexOf("\n\n", start) + 1);
        assertThat(section).contains("- Link entity [ENTITY, EXTEND]: " + LINK + "\n")
                .contains("- LinkService [SERVICE, EXTEND, static analysis]: " + LINK_SERVICE + "\n")
                .as("new components have no path to copy").doesNotContain("V3__campaign.sql");
        assertThat(user).contains("Empty for a new component");
        assertThat(client.requests.getFirst().system()).contains("Set path on every existing component")
                .contains(PromptTemplates.INDEX_PATHS_RULE);
    }

    @Test
    void aDesignWithoutAnIndexIsLeftAsWritten() throws Exception {
        DesignSpec written = Payloads.design(RiskLevel.MEDIUM);
        client.answer = r -> written;

        DesignSpec design = (DesignSpec) ((StageResult.Success) run(AgentRole.ARCHITECTURE,
                Contexts.of(AgentRole.ARCHITECTURE, "x", spec, Payloads.impact()))).payload();

        assertThat(design).isEqualTo(written);
        assertThat(client.requests.getFirst().user()).doesNotContain(PromptTemplates.IMPACTED_PATHS_HEADING);
    }

    @Test
    void aChangeSetThatFailsReportsTheSourcesItRequested() {
        client.answer = r -> new ChangeSet("HEAD", List.of(), "./mvnw verify", List.of(), "revert", List.of("T1"),
                List.of(BASE + "link/Link.java", "LinkService"));
        assertThatThrownBy(() -> run(AgentRole.IMPLEMENTATION, Contexts.of(AgentRole.IMPLEMENTATION, "x")))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.requestedSources()).containsExactly(BASE + "link/Link.java", "LinkService");
                });
        assertThat(client.requests.getFirst().system()).contains(PromptTemplates.REQUESTED_SOURCES_RULE)
                .contains("do not guess its content: list its path");
        assertThat(client.requests.getFirst().user()).contains("\"requestedSources\"")
                .contains("Paths from the index that must be shown in the next attempt");

        String existing = BASE + "api/LinkController.java";
        client.answer = r -> new ChangeSet("HEAD", List.of(new ChangeSet.FileChange(existing,
                ChangeSet.Operation.CREATE, "java", "rewrite", ChangeSet.ContentKind.FULL, "class LinkController {}",
                List.of())), "./mvnw verify", List.of(), "revert", List.of(), List.of(LINK));
        StageContext base = Contexts.of(AgentRole.IMPLEMENTATION, "x");
        StageContext sandboxed = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null, null, REPO,
                List.of(), new AgentSettings(0.5, true, false, workspace, dir.resolve("sandboxes"),
                Duration.ofMinutes(1)));
        assertThatThrownBy(() -> run(AgentRole.IMPLEMENTATION, sandboxed))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.getMessage()).contains("CREATE of existing file " + existing);
                    assertThat(f.requestedSources()).containsExactly(LINK);
                    assertThat(f.trace()).isNotNull();
                });
    }
}
