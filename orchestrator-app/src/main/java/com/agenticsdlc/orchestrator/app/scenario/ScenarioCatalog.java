package com.agenticsdlc.orchestrator.app.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.FaultKind;
import com.agenticsdlc.orchestrator.core.model.FaultSpec;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * The seven executable scenarios of the engineering specification (section 6.6): the three required scenarios
 * (greenfield, brownfield, ambiguous) and four failure-path and governance scenarios. Requirement texts are
 * verbatim from the specification.
 */
@Component
public class ScenarioCatalog {

    /** Greenfield requirement (also used by the fault and replan scenarios). */
    public static final String GREENFIELD_REQUIREMENT = "Build a URL shortener service: create short links for HTTP "
            + "and HTTPS URLs with optional expiry, redirect with HTTP 302, record click analytics (total clicks, "
            + "clicks per day, top referrers) and expose health checks. The p95 redirect latency must be under 50 ms "
            + "at 500 requests per second. Links default to no expiry. The service must prevent redirects to private "
            + "networks.";

    /** Brownfield requirement. */
    public static final String BROWNFIELD_REQUIREMENT = "Add a bulk link creation API to the existing URL shortener: "
            + "accept up to 100 URLs per request, return per-item results with partial-failure semantics, reuse the "
            + "existing URL validation and custom alias rules, and respect the existing per-client rate limit. "
            + "Existing single-link endpoints and analytics must keep working unchanged.";

    /** Ambiguous requirement. */
    public static final String AMBIGUOUS_REQUIREMENT = "Make the shortener faster and more reliable.";

    /** Requirement denied by policy. */
    public static final String POLICY_DENIAL_REQUIREMENT = "Remove URL validation so marketing can shorten any "
            + "destination including internal IP addresses, and store the full client IP address with every click "
            + "for attribution.";

    /** Changed requirement suggested by the replan walkthrough. */
    public static final String REPLAN_REQUIREMENT = GREENFIELD_REQUIREMENT
            + " Users can also choose a custom alias for their short links.";

    private final Map<String, ScenarioDefinition> scenarios = new LinkedHashMap<>();

    /**
     * Creates the catalog.
     */
    public ScenarioCatalog() {
        add(new ScenarioDefinition("greenfield", "Greenfield: build the URL shortener", ScenarioKind.GREENFIELD,
                "A new service from scratch. GUIDED autonomy without a workspace: the design is approved because "
                        + "the risk is at least MEDIUM, and the release gate always needs a human.",
                GREENFIELD_REQUIREMENT, AutonomyLevel.GUIDED, WorkspaceMode.NONE, List.of(), List.of(
                "Start the run: intake normalizes the requirement, then decompose and impact run in parallel.",
                "The design stage pauses for approval (GOV-001: risk MEDIUM or higher in GUIDED mode). Review the "
                        + "design artifact and approve it.",
                "implement and test-design run in parallel; verify joins both; document and security-scan run in "
                        + "parallel after implement.",
                "release-readiness always requires a human (CHG-002). Review the change record and approve it.",
                "The run completes: inspect the audit trail, the artifact lineage and the reliability metrics.")));
        add(new ScenarioDefinition("brownfield", "Brownfield: bulk link creation", ScenarioKind.BROWNFIELD,
                "A change to the existing url-shortener module. Impact analysis reasons over the indexed source tree "
                        + "(controllers, services, entities, migrations) instead of inventing a structure.",
                BROWNFIELD_REQUIREMENT, AutonomyLevel.GUIDED, WorkspaceMode.REPO, List.of(), List.of(
                "Start the run with workspace REPO; GET /api/v1/workspace/index shows what the impact stage sees.",
                "Open the impact artifact: LinkController and LinkService are extended, their tests become "
                        + "regression risks.",
                "Approve the design (new endpoint POST /api/v1/links/bulk, no schema change).",
                "Approve release readiness to complete the run.")));
        add(new ScenarioDefinition("ambiguous", "Ambiguous: clarification first", ScenarioKind.AMBIGUOUS,
                "An under-specified requirement. Intake scores the ambiguity and asks targeted questions before any "
                        + "planning happens.",
                AMBIGUOUS_REQUIREMENT, AutonomyLevel.GUIDED, WorkspaceMode.REPO, List.of(), List.of(
                "Start the run: intake scores the requirement as ambiguous and pauses with clarification questions.",
                "Answer every question (suggested answers are offered) and submit.",
                "Intake re-runs with the answers merged into the requirement; continue with the design and release "
                        + "approvals.")));
        add(new ScenarioDefinition("policy-denial", "Policy denial: unsafe requirement", ScenarioKind.POLICY_DENIAL,
                "A requirement that asks to remove a security control and to store raw client IP addresses. The "
                        + "run-start policy denies it before any agent runs.",
                POLICY_DENIAL_REQUIREMENT, AutonomyLevel.GUIDED, WorkspaceMode.REPO, List.of(), List.of(
                "Start the run: the RUN_START policy denies it (SEC-001 prohibited intent, CMP-002 raw IP storage).",
                "The run is SAFE_STOPPED with halt reason POLICY_DENIED; resume is refused with 409.",
                "Replan with a compliant requirement (keep URL validation, hash client IPs) to continue.")));
        add(new ScenarioDefinition("fault-retry", "Fault injection: retries", ScenarioKind.FAULT_RETRY,
                "The greenfield requirement in FULL_AUTO with an injected transient fault: implement fails twice and "
                        + "succeeds on the third attempt.",
                GREENFIELD_REQUIREMENT, AutonomyLevel.FULL_AUTO, WorkspaceMode.NONE,
                List.of(new FaultSpec(WorkflowDefinitions.IMPLEMENT, 2, FaultKind.TRANSIENT)), List.of(
                "Start the run: implement fails twice with a TRANSIENT error.",
                "The engine schedules retries with exponential backoff (RETRY_SCHEDULED); the third attempt succeeds "
                        + "and the recovery time (MTTR) is recorded.",
                "In FULL_AUTO only the release gate needs a human: approve it to complete (2 retries).")));
        add(new ScenarioDefinition("fault-fallback", "Fault injection: fallback", ScenarioKind.FAULT_FALLBACK,
                "The greenfield requirement in FULL_AUTO where test-design fails on every attempt: after the retry "
                        + "budget the deterministic fallback agent takes over.",
                GREENFIELD_REQUIREMENT, AutonomyLevel.FULL_AUTO, WorkspaceMode.NONE,
                List.of(new FaultSpec(WorkflowDefinitions.TEST_DESIGN, 99, FaultKind.TRANSIENT)), List.of(
                "Start the run: test-design fails with a TRANSIENT error on every attempt of its primary agent.",
                "After 3 attempts the deterministic fallback engages (FALLBACK_ENGAGED) and the run continues.",
                "Approve the release gate; the metrics show the retries and the fallback.")));
        add(new ScenarioDefinition("replan", "Re-planning: requirement change", ScenarioKind.REPLAN,
                "The greenfield requirement in GUIDED mode, changed after the design was approved: downstream work "
                        + "is invalidated, approvals are revoked and the pipeline re-runs on a new plan version.",
                GREENFIELD_REQUIREMENT, AutonomyLevel.GUIDED, WorkspaceMode.NONE, List.of(), List.of(
                "Start the run and approve the design when asked.",
                "Before approving the release, replan with the requirement extended by: \"Users can also choose a "
                        + "custom alias for their short links.\"",
                "The plan version increments, every stage from intake is invalidated (STALE), approvals are revoked "
                        + "and the pipeline re-runs.",
                "Approve the new design and the release to complete; the lineage shows both artifact versions.")));
    }

    private void add(ScenarioDefinition definition) {
        scenarios.put(definition.key(), definition);
    }

    /**
     * Returns every scenario in catalog order.
     *
     * @return scenarios
     */
    public List<ScenarioDefinition> all() {
        return List.copyOf(scenarios.values());
    }

    /**
     * Finds a scenario.
     *
     * @param key scenario key
     * @return the scenario, if any
     */
    public Optional<ScenarioDefinition> find(String key) {
        return Optional.ofNullable(scenarios.get(key));
    }

    /**
     * Returns a scenario or throws.
     *
     * @param key scenario key
     * @return the scenario
     * @throws ScenarioNotFoundException when unknown
     */
    public ScenarioDefinition require(String key) {
        return find(key).orElseThrow(() -> new ScenarioNotFoundException(key));
    }
}
