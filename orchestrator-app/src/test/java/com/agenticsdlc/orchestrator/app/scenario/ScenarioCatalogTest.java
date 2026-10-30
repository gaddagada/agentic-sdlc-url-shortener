package com.agenticsdlc.orchestrator.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * The scenario catalog matches the engineering specification (section 6.6) verbatim.
 */
class ScenarioCatalogTest {

    private static final String GREENFIELD = "Build a URL shortener service: create short links for HTTP and HTTPS "
            + "URLs with optional expiry, redirect with HTTP 302, record click analytics (total clicks, clicks per "
            + "day, top referrers) and expose health checks. The p95 redirect latency must be under 50 ms at 500 "
            + "requests per second. Links default to no expiry. The service must prevent redirects to private "
            + "networks.";
    private static final String BROWNFIELD = "Add campaign labels to the existing URL shortener. A link created "
            + "through POST /api/v1/links or POST /api/v1/links/bulk may carry an optional campaign label (1 to 64 "
            + "characters: letters, digits, dash, underscore) that is stored with the link. "
            + "GET /api/v1/analytics/campaigns/{campaign} returns the number of links and the total and per-day "
            + "clicks of that campaign over the last 30 days. Existing links without a label keep working, and the "
            + "schema change ships as a new Flyway migration.";

    private static final String AMBIGUOUS = "Make the shortener faster and more reliable.";
    private static final String POLICY_DENIAL = "Remove URL validation so marketing can shorten any destination "
            + "including internal IP addresses, and store the full client IP address with every click for "
            + "attribution.";

    private final ScenarioCatalog catalog = new ScenarioCatalog();

    @Test
    void containsTheSevenScenariosInOrder() {
        assertThat(catalog.all()).extracting(ScenarioDefinition::key).containsExactly("greenfield", "brownfield",
                "ambiguous", "policy-denial", "fault-retry", "fault-fallback", "replan");
        assertThat(catalog.all()).extracting(ScenarioDefinition::kind).containsExactly(ScenarioKind.GREENFIELD,
                ScenarioKind.BROWNFIELD, ScenarioKind.AMBIGUOUS, ScenarioKind.POLICY_DENIAL, ScenarioKind.FAULT_RETRY,
                ScenarioKind.FAULT_FALLBACK, ScenarioKind.REPLAN);
    }

    @Test
    void requirementsAreVerbatimFromTheSpecification() {
        assertThat(catalog.require("greenfield").requirement()).isEqualTo(GREENFIELD);
        assertThat(catalog.require("brownfield").requirement()).isEqualTo(BROWNFIELD);
        assertThat(catalog.require("ambiguous").requirement()).isEqualTo(AMBIGUOUS);
        assertThat(catalog.require("policy-denial").requirement()).isEqualTo(POLICY_DENIAL);
        assertThat(catalog.require("fault-retry").requirement()).isEqualTo(GREENFIELD);
        assertThat(catalog.require("fault-fallback").requirement()).isEqualTo(GREENFIELD);
        assertThat(catalog.require("replan").requirement()).isEqualTo(GREENFIELD);
        assertThat(ScenarioCatalog.REPLAN_REQUIREMENT).startsWith(GREENFIELD).contains("custom alias");
    }

    @Test
    void optionsMatchTheSpecificationTable() {
        assertOptions("greenfield", AutonomyLevel.GUIDED, WorkspaceMode.NONE, List.of());
        assertOptions("brownfield", AutonomyLevel.GUIDED, WorkspaceMode.REPO, List.of());
        assertOptions("ambiguous", AutonomyLevel.GUIDED, WorkspaceMode.REPO, List.of());
        assertOptions("policy-denial", AutonomyLevel.GUIDED, WorkspaceMode.REPO, List.of());
        assertOptions("fault-retry", AutonomyLevel.FULL_AUTO, WorkspaceMode.NONE,
                List.of(new FaultSpec("implement", 2, FaultKind.TRANSIENT)));
        assertOptions("fault-fallback", AutonomyLevel.FULL_AUTO, WorkspaceMode.NONE,
                List.of(new FaultSpec("test-design", 99, FaultKind.TRANSIENT)));
        assertOptions("replan", AutonomyLevel.GUIDED, WorkspaceMode.NONE, List.of());
    }

    @Test
    void everyScenarioHasATitleDescriptionAndWalkthroughAndValidFaultStages() {
        WorkflowGraph graph = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
        assertThat(catalog.all()).allSatisfy(s -> {
            assertThat(s.title()).isNotBlank();
            assertThat(s.description()).isNotBlank();
            assertThat(s.walkthrough()).isNotEmpty().allSatisfy(step -> assertThat(step).isNotBlank());
            assertThat(s.requirement().length()).isBetween(10, 4000);
            s.faults().forEach(f -> assertThat(graph.contains(f.stageId())).as(f.stageId()).isTrue());
        });
        assertThat(catalog.require("replan").walkthrough()).anySatisfy(step -> assertThat(step).contains("replan"));
        assertThat(catalog.require("brownfield").title()).contains("campaign labels");
        assertThat(catalog.require("brownfield").walkthrough())
                .anySatisfy(step -> assertThat(step).contains("impact artifact", "BulkLinkController", "migration"))
                .anySatisfy(step -> assertThat(step).contains("GOV-001"));
    }

    @Test
    void unknownKeysAreReported() {
        assertThat(catalog.find("nope")).isEmpty();
        assertThatThrownBy(() -> catalog.require("nope")).isInstanceOf(ScenarioNotFoundException.class)
                .hasMessage("Scenario not found: nope");
    }

    private void assertOptions(String key, AutonomyLevel autonomy, WorkspaceMode workspace, List<FaultSpec> faults) {
        ScenarioDefinition scenario = catalog.require(key);
        assertThat(scenario.autonomy()).as(key).isEqualTo(autonomy);
        assertThat(scenario.workspace()).as(key).isEqualTo(workspace);
        assertThat(scenario.faults()).as(key).isEqualTo(faults);
    }
}
