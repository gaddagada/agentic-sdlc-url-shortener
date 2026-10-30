package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AcceptanceCriterion;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Category;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Requirement;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan.CaseType;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan.Level;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan.TestCase;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan.TestLevel;

/**
 * Deterministic test designer ({@code test-design}): one POSITIVE and one NEGATIVE case per acceptance
 * criterion, a PERFORMANCE case per performance NFR, a SECURITY case per security or compliance NFR, a
 * RECOVERY case per reliability NFR and an integration case per operability NFR. The coverage matrix maps
 * every requirement to its cases; requirements without automated coverage are listed as risks.
 */
public final class TestDesignAgent implements StageAgent {

    /**
     * Creates the agent.
     */
    public TestDesignAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.TEST_DESIGN;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        RequirementSpec spec = context.require(RequirementSpec.class);
        DesignSpec design = context.find(DesignSpec.class).orElse(null);
        TestPlan plan = plan(spec, design);
        return StageResult.success(plan, plan.cases().size() + " test cases on " + plan.levels().size()
                + " levels; " + plan.risksNotCovered().size() + " requirements without automated coverage");
    }

    /**
     * Builds the test plan.
     *
     * @param spec requirement specification
     * @param design design or null
     * @return the test plan
     */
    public TestPlan plan(RequirementSpec spec, @Nullable DesignSpec design) {
        boolean api = design != null && !design.apiChanges().isEmpty();
        boolean data = design != null && !design.dataModelChanges().isEmpty();
        List<TestLevel> levels = new ArrayList<>();
        levels.add(new TestLevel(Level.UNIT, "services, validators and domain rules", "JUnit 6, AssertJ, Mockito"));
        if (api || data) {
            levels.add(new TestLevel(Level.SLICE, "controllers and repositories in isolation",
                    "@WebMvcTest with @MockitoBean, @DataJpaTest with Flyway on H2"));
        }
        levels.add(new TestLevel(Level.INTEGRATION, "end-to-end flows through the HTTP API",
                "@SpringBootTest with MockMvc, H2, Awaitility"));
        if (api) {
            levels.add(new TestLevel(Level.CONTRACT, "API contract of the changed endpoints",
                    "OpenAPI contract assertions"));
        }
        boolean performance = spec.nonFunctional().stream().anyMatch(n -> n.category() == Category.PERFORMANCE);
        boolean security = spec.nonFunctional().stream()
                .anyMatch(n -> n.category() == Category.SECURITY || n.category() == Category.COMPLIANCE)
                || (design != null && !design.securityConsiderations().isEmpty());
        if (performance) {
            levels.add(new TestLevel(Level.PERFORMANCE, "latency and throughput targets", "k6 or Gatling load test"));
        }
        if (security) {
            levels.add(new TestLevel(Level.SECURITY, "input validation, SSRF guard, rate limiting",
                    "negative tests and an OWASP ZAP baseline scan"));
        }

        List<TestCase> cases = new ArrayList<>();
        for (AcceptanceCriterion ac : spec.acceptanceCriteria()) {
            List<String> traces = new ArrayList<>(ac.traces());
            traces.add(ac.id());
            Level level = api ? Level.INTEGRATION : Level.UNIT;
            cases.add(new TestCase(next(cases), ac.id() + " happy path: " + Text.shorten(ac.then(), 70), level,
                    CaseType.POSITIVE, ac.given(), List.of("Given " + ac.given(), "When " + ac.when(),
                    "Then " + ac.then()), ac.then(), traces));
            cases.add(new TestCase(next(cases), ac.id() + " rejection of invalid input", level, CaseType.NEGATIVE,
                    ac.given().replaceFirst("^a valid", "an invalid"), List.of("Given an invalid or unauthorized "
                    + "variant of: " + ac.given(), "When " + ac.when(), "Then the request is rejected"),
                    "the request is rejected with a clear error (Problem Details) and no state changes", traces));
        }
        for (Requirement nfr : spec.nonFunctional()) {
            List<String> traces = List.of(nfr.id());
            switch (nfr.category()) {
                case PERFORMANCE -> cases.add(new TestCase(next(cases), nfr.id() + " load test", Level.PERFORMANCE,
                        CaseType.BOUNDARY, "production-like data set and environment",
                        List.of("Warm up the service", "Run the load profile from " + nfr.id(),
                                "Collect latency percentiles"), "the target of " + nfr.id() + " is met: "
                        + nfr.text(), traces));
                case SECURITY, COMPLIANCE -> cases.add(new TestCase(next(cases), nfr.id() + " security check",
                        Level.SECURITY, CaseType.SECURITY, "the service is running",
                        List.of("Send malicious or policy-violating input for " + nfr.id(),
                                "Inspect responses, stored data and logs"),
                        "the control of " + nfr.id() + " holds: " + nfr.text(), traces));
                case RELIABILITY -> cases.add(new TestCase(next(cases), nfr.id() + " failure and recovery",
                        Level.INTEGRATION, CaseType.RECOVERY, "the service is running with its dependencies",
                        List.of("Inject a dependency failure", "Observe behaviour and recovery"),
                        "the service degrades gracefully and recovers: " + nfr.text(), traces));
                case OPERABILITY -> cases.add(new TestCase(next(cases), nfr.id() + " operational endpoints",
                        Level.INTEGRATION, CaseType.POSITIVE, "the service is running",
                        List.of("Call the health and metrics endpoints"), "the endpoints report the expected state: "
                        + nfr.text(), traces));
                case null, default -> {
                    // USABILITY and OTHER: covered by acceptance criteria if measurable, else listed as risk
                }
            }
        }
        Map<String, String> coverage = new LinkedHashMap<>();
        List<String> notCovered = new ArrayList<>();
        for (Requirement requirement : spec.allRequirements()) {
            List<String> ids = cases.stream().filter(c -> c.traces().contains(requirement.id())).map(TestCase::id)
                    .toList();
            if (ids.isEmpty()) {
                notCovered.add(requirement.id() + " (" + requirement.category() + ") has no automated test; "
                        + "manual review required");
            } else {
                coverage.put(requirement.id(), String.join(",", ids));
            }
        }
        String strategy = "Risk-based test pyramid: unit tests for rules, slice tests for web and persistence "
                + "boundaries, integration tests for the acceptance criteria" + (performance ? ", a load test for "
                + "the performance targets" : "") + (security ? ", negative and security tests for the controls" : "")
                + ". Every requirement is traced to at least one case or listed as not covered.";
        return new TestPlan(strategy, levels, cases, coverage, notCovered);
    }

    private static String next(List<TestCase> cases) {
        return "TC-" + (cases.size() + 1);
    }
}
