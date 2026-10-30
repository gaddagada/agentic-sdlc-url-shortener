package com.agenticsdlc.orchestrator.core.testsupport;

import java.util.List;
import java.util.Map;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;

/**
 * Minimal valid payloads for fake agents.
 */
public final class Payloads {

    private Payloads() {
    }

    public static ArtifactPayload forRole(AgentRole role) {
        return switch (role) {
            case REQUIREMENTS -> requirementSpec(RiskLevel.LOW);
            case PLANNING -> taskPlan();
            case CODEBASE_ANALYSIS -> impact();
            case ARCHITECTURE -> design(RiskLevel.MEDIUM);
            case IMPLEMENTATION -> changeSet("url-shortener/src/main/java/com/example/Feature.java", "class Feature {}");
            case TEST_DESIGN -> testPlan();
            case VERIFICATION -> verification(true);
            case DOCUMENTATION -> docs();
            case SECURITY_REVIEW -> security();
            case RELEASE -> release(ReleaseReadiness.GoNoGo.GO);
        };
    }

    public static RequirementSpec requirementSpec(RiskLevel risk) {
        return new RequirementSpec("Create short links.", "Create short links", RequirementSpec.Classification.GREENFIELD,
                List.of("Links"), List.of(), List.of(new RequirementSpec.Requirement("FR-1", "Create short links",
                RequirementSpec.Priority.MUST, RequirementSpec.FUNCTIONAL_CATEGORY, false)), List.of(),
                List.of(new RequirementSpec.AcceptanceCriterion("AC-1", "a client", "it creates a link",
                        "the link exists", List.of("FR-1"))), List.of(), List.of(),
                new RequirementSpec.AmbiguityReport(0.0, List.of(), List.of()), new RiskAssessment(risk, List.of()));
    }

    public static TaskPlan taskPlan() {
        return new TaskPlan(List.of(new TaskPlan.Task("T1", "Implement", "Implement FR-1",
                TaskPlan.TaskType.IMPLEMENT, List.of(), TaskPlan.Estimate.M, TaskPlan.Owner.AGENT, List.of("FR-1"),
                "done")), List.of("T1"), List.of(List.of("T1")), "single task");
    }

    public static ImpactAnalysis impact() {
        return new ImpactAnalysis("none", 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), "No existing codebase");
    }

    public static DesignSpec design(RiskLevel risk) {
        return new DesignSpec("Overview", List.of(new DesignSpec.Component("FeatureController", "endpoints",
                "CONTROLLER", true)), List.of(), List.of(), List.of(), List.of(), List.of("Validate input"),
                new DesignSpec.DataHandling(List.of(), "n/a", "n/a"),
                List.of(new DesignSpec.Alternative("A", List.of(), List.of(), true),
                        new DesignSpec.Alternative("B", List.of(), List.of(), false)),
                new RiskAssessment(risk, risk == RiskLevel.LOW ? List.of() : List.of("public API change")),
                List.of(new DesignSpec.Decision("Style", "Layered", "Simple")));
    }

    public static ChangeSet changeSet(String path, String content) {
        return new ChangeSet("HEAD", List.of(
                new ChangeSet.FileChange(path, ChangeSet.Operation.CREATE, "java", "feature",
                        ChangeSet.ContentKind.FULL, content, List.of("T1")),
                new ChangeSet.FileChange(path.replace("src/main", "src/test").replace(".java", "Test.java"),
                        ChangeSet.Operation.CREATE, "java", "test", ChangeSet.ContentKind.FULL, "class FeatureTest {}",
                        List.of("T1"))),
                "./mvnw -pl url-shortener verify", List.of(), "Revert the commit", List.of("T1"));
    }

    public static TestPlan testPlan() {
        return new TestPlan("pyramid", List.of(new TestPlan.TestLevel(TestPlan.Level.UNIT, "rules", "JUnit")),
                List.of(new TestPlan.TestCase("TC-1", "happy path", TestPlan.Level.UNIT, TestPlan.CaseType.POSITIVE,
                        "", List.of("step"), "ok", List.of("FR-1", "AC-1"))), Map.of("FR-1", "TC-1"), List.of());
    }

    public static VerificationReport verification(boolean passed) {
        List<Check> checks = List.of(passed ? Check.pass("tests-accompany-production-changes", "ok")
                : Check.fail("tests-accompany-production-changes", "no test for: Feature"));
        VerificationReport.Traceability traceability = new VerificationReport.Traceability(1, 1, List.of());
        return new VerificationReport(checks, traceability, null, passed,
                passed ? List.of() : List.of("Add tests for: Feature"));
    }

    public static DocumentationBundle docs() {
        return new DocumentationBundle("readme", "api", "runbook", "changelog", List.of());
    }

    public static SecurityReview security() {
        return new SecurityReview(List.of(), List.of(Check.pass("input-validation-in-design", "ok")), List.of(),
                List.of(), true);
    }

    public static ReleaseReadiness release(ReleaseReadiness.GoNoGo decision) {
        return new ReleaseReadiness(List.of(new ReleaseReadiness.ChecklistItem("Verification passed", "PASS", "ok")),
                decision, decision == ReleaseReadiness.GoNoGo.GO_WITH_CONDITIONS ? List.of("condition") : List.of(),
                new ReleaseReadiness.ChangeRecord("summary", RiskLevel.LOW, "Revert the commit", List.of(), List.of(),
                        "pending"), "notes");
    }
}
