package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.ApiChange;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

class ArchitectureAgentTest {

    private static final String CAMPAIGN = "Add GET /api/v1/analytics/campaigns/{campaign} to the existing URL "
            + "shortener: it returns the total clicks of all links carrying the campaign label. Existing redirects "
            + "via GET /{code} must keep working unchanged.";

    private final ArchitectureAgent agent = new ArchitectureAgent();
    private CodebaseIndex index;

    @BeforeEach
    void index(@TempDir Path dir) {
        index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir));
    }

    private static RequirementSpec spec(String requirement, WorkspaceMode mode) {
        return new RequirementsAgent().analyze(requirement, List.of(), Map.of(), null, mode, 0.5);
    }

    private DesignSpec brownfield(String requirement) {
        RequirementSpec spec = spec(requirement, WorkspaceMode.REPO);
        ImpactAnalysis impact = new ImpactAnalyzer().analyze(index, spec);
        DesignSpec design = agent.design(spec, impact);
        design.validate();
        return design;
    }

    @Test
    void anExplicitlyNamedNewEndpointBecomesAnAddedApiChange() {
        DesignSpec design = brownfield(CAMPAIGN);

        assertThat(design.apiChanges()).first().satisfies((ApiChange change) -> {
            assertThat(change.method()).isEqualTo("GET");
            assertThat(change.path()).isEqualTo("/api/v1/analytics/campaigns/{campaign}");
            assertThat(change.summary()).startsWith(ArchitectureAgent.EXPLICIT_ENDPOINT_SUMMARY)
                    .contains("campaign label");
            assertThat(change.breaking()).isFalse();
            assertThat(change.requestSchema()).isEqualTo("none");
            assertThat(change.errorResponses()).containsExactly("404 unknown campaign");
        });
        assertThat(design.apiChanges()).as("a sentence that only asks to preserve behaviour adds nothing")
                .noneMatch(c -> c.path().equals("/{code}"));
        assertThat(design.risk().factors()).anySatisfy(f -> assertThat(f).startsWith("public API change"));
        assertThat(design.sequenceFlows()).anySatisfy(f -> assertThat(f)
                .startsWith("Client -> GET /api/v1/analytics/campaigns/{campaign} -> AnalyticsController -> "));
    }

    @Test
    void anExplicitEndpointWithoutAnImpactedControllerGoesToTheExistingControllerOfItsResource() {
        RequirementSpec spec = spec("Add GET /api/v1/analytics/campaigns/{campaign} to the existing URL shortener.",
                WorkspaceMode.REPO);
        ImpactAnalysis impact = new ImpactAnalysis("ws", 10, true, List.of(
                new ImpactAnalysis.ImpactedComponent("AnalyticsService", ImpactAnalysis.ComponentType.SERVICE,
                        "url-shortener/src/main/java/x/AnalyticsService.java", 0.8, List.of("name"),
                        ImpactAnalysis.ChangeNature.EXTEND),
                new ImpactAnalysis.ImpactedComponent("AnalyticsController", ImpactAnalysis.ComponentType.CONTROLLER,
                        "url-shortener/src/main/java/x/AnalyticsController.java", 0.4, List.of("uses"),
                        ImpactAnalysis.ChangeNature.REGRESSION_RISK)), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "summary");

        DesignSpec design = agent.design(spec, impact);

        assertThat(design.components()).filteredOn(c -> "CONTROLLER".equals(c.type())).singleElement()
                .satisfies(c -> {
                    assertThat(c.name()).as("not a new 'AnalyticController'").isEqualTo("AnalyticsController");
                    assertThat(c.isNew()).isFalse();
                });
    }

    @Test
    void explicitEndpointsAreReadFromTheStatementAndTheFunctionalRequirementsOncePerMethodAndPath() {
        RequirementSpec spec = spec("Build a URL shortener. Expose POST /api/v2/links, and GET /api/v2/links/{code}. "
                + "POST /api/v2/links/ accepts a JSON body; DELETE /api/v2/links/{code}: deactivates a link. "
                + "FORGET /nothing and the path and/or prose /etc must not match.", WorkspaceMode.NONE);

        assertThat(ArchitectureAgent.explicitEndpoints(spec)).extracting(c -> c.method() + " " + c.path())
                .containsExactly("POST /api/v2/links", "GET /api/v2/links/{code}", "DELETE /api/v2/links/{code}");
        assertThat(ArchitectureAgent.explicitEndpoints(spec).getFirst().requestSchema())
                .isEqualTo("request body as specified by the requirement");
        assertThat(ArchitectureAgent.explicitEndpoints(spec).getFirst().errorResponses())
                .containsExactly("400 validation error");

        DesignSpec design = agent.design(spec, new ImpactAnalyzer().proposeGreenfield(spec));
        design.validate();
        assertThat(design.apiChanges()).extracting(c -> c.method() + " " + c.path())
                .startsWith("POST /api/v2/links", "GET /api/v2/links/{code}", "DELETE /api/v2/links/{code}")
                .doesNotHaveDuplicates();
    }

    @Test
    void anExplicitlyNamedEndpointThatAlreadyExistsIsNotReportedAsAdded() {
        DesignSpec design = brownfield("Extend POST /api/v1/links of the existing URL shortener with bulk creation of "
                + "up to 50 links per request.");

        assertThat(design.apiChanges()).noneMatch(c -> c.method().equals("POST") && c.path().equals("/api/v1/links"));
        assertThat(design.apiChanges()).extracting(ApiChange::path).contains("/api/v1/links/bulk");
    }

    @Test
    void requirementsWithoutExplicitPathsKeepTheKeywordDerivedChanges() {
        String requirement = "Add a bulk link creation API to the existing URL shortener: accept up to 100 URLs per "
                + "request and reuse the existing URL validation.";
        assertThat(ArchitectureAgent.explicitEndpoints(spec(requirement, WorkspaceMode.REPO))).isEmpty();

        DesignSpec design = brownfield(requirement);

        assertThat(design.apiChanges()).extracting(c -> c.method() + " " + c.path())
                .containsExactly("POST /api/v1/links/bulk");
        assertThat(design.apiChanges()).noneMatch(c -> c.summary().startsWith(
                ArchitectureAgent.EXPLICIT_ENDPOINT_SUMMARY));
    }
}
