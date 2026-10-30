package com.agenticsdlc.orchestrator.core.agent.deterministic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.DependencyReview;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview.StrideCategory;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview.ThreatStatus;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.BuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;

class SecurityReviewAgentTest {

    private final RequirementSpec spec = new RequirementsAgent().analyze("Build a URL shortener service: create short "
            + "links, redirect with HTTP 302 and record click analytics with unique visitors.", List.of(), Map.of(),
            null, WorkspaceMode.NONE, 0.5);
    private final ImpactAnalysis impact = new ImpactAnalyzer().proposeGreenfield(spec);
    private final DesignSpec design = new ArchitectureAgent().design(spec, impact);
    private final ChangeSet changeSet = new ImplementationAgent().implement(design, spec, impact, null, List.of());
    private final SecurityReviewAgent agent = new SecurityReviewAgent();

    private DesignSpec withSecurity(List<String> considerations, DesignSpec.DataHandling handling) {
        return new DesignSpec(design.overview(), design.components(), design.apiChanges(), design.dataModelChanges(),
                design.sequenceFlows(), design.nfrStrategies(), considerations, handling,
                design.alternativesConsidered(), design.risk(), design.decisions());
    }

    @Test
    void deterministicDesignPassesWithStrideThreats() {
        SecurityReview review = agent.review(design, changeSet);
        review.validate();
        assertThat(review.passed()).isTrue();
        assertThat(review.checks()).extracting(Check::name).containsExactly("input-validation-in-design",
                "ssrf-open-redirect-guard", "no-raw-pii", "rate-limiting-on-public-create", "no-secrets-in-change-set",
                "mutating-endpoints-auth-note");
        assertThat(review.checks()).allSatisfy(c -> assertThat(c.passed()).isTrue());
        assertThat(review.threats()).extracting(SecurityReview.Threat::category)
                .contains(StrideCategory.values());
        assertThat(review.findings()).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.LOW));
        assertThat(review.complianceNotes()).anySatisfy(n -> assertThat(n).contains("client IP address"));
    }

    @Test
    void missingControlsProduceHighFindingsAndFailTheReview() {
        DesignSpec weak = withSecurity(List.of("Use HTTPS"),
                new DesignSpec.DataHandling(List.of("client IP address"), "", ""));
        SecurityReview review = agent.review(weak, changeSet);
        review.validate();
        assertThat(review.passed()).isFalse();
        assertThat(review.findings()).extracting(SecurityReview.Finding::severity)
                .contains(Severity.HIGH, Severity.MEDIUM, Severity.LOW);
        assertThat(review.threats()).anySatisfy(t -> assertThat(t.status()).isEqualTo(ThreatStatus.OPEN));
        assertThat(review.checks()).filteredOn(c -> !c.passed()).extracting(Check::name).contains(
                "input-validation-in-design", "ssrf-open-redirect-guard", "no-raw-pii", "rate-limiting-on-public-create",
                "mutating-endpoints-auth-note");
    }

    @Test
    void secretsInTheChangeSetAreCritical() {
        ChangeSet leaky = new ChangeSet("HEAD", List.of(new ChangeSet.FileChange("url-shortener/src/main/java/A.java",
                ChangeSet.Operation.CREATE, "java", "a", ChangeSet.ContentKind.FULL, "token = ghp_abcdefghijklmnopqrstuvwxyz",
                List.of())), "./mvnw verify", List.of(), "revert", List.of());
        SecurityReview review = agent.review(design, leaky);
        assertThat(review.passed()).isFalse();
        assertThat(review.findings()).anySatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.CRITICAL));
    }

    @Test
    void repoRunsInventoryTheBuildFilesAndRecordTheConfiguredScan(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>edge</artifactId><version>RELEASE</version></dependency>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.17</version></dependency>
                  </dependencies>
                </project>
                """);
        StageContext base = Contexts.of(AgentRole.SECURITY_REVIEW, "req", RunOptions.defaults()
                .withWorkspace(WorkspaceMode.REPO), null, List.of(), design, changeSet);
        StageContext ctx = Contexts.withSettings(base, new AgentSettings(0.5, false, false, dir, dir.resolve("sb"),
                Duration.ofMinutes(1)).withDependencyScan("./mvnw -B dependency-check", Duration.ofSeconds(30)));
        List<String> scans = new ArrayList<>();
        BuildRunner runner = (workDir, command, timeout) -> {
            scans.add(command);
            return new BuildResult(true, command, 0, 1L, "ok", List.of(), List.of("clean"));
        };

        StageResult result = new SecurityReviewAgent(runner).execute(ctx);

        SecurityReview review = (SecurityReview) ((StageResult.Success) result).payload();
        review.validate();
        assertThat(review.dependencies()).extracting(DependencyRecord::coordinate).containsExactly(
                "com.acme:edge:RELEASE", "org.slf4j:slf4j-api:2.0.17");
        assertThat(review.checks()).filteredOn(c -> c.name().equals(DependencyReview.INVENTORY_CHECK)).singleElement()
                .satisfies(c -> assertThat(c.passed()).isFalse());
        assertThat(review.findings()).anySatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.title()).isEqualTo("Dependency hygiene: LATEST or RELEASE versions (1)");
        });
        assertThat(review.passed()).isFalse();
        assertThat(review.checks()).filteredOn(c -> c.name().equals(DependencyReview.SCAN_CHECK)).singleElement()
                .satisfies(c -> {
                    assertThat(c.blocking()).as("no sandbox: reported, not run").isFalse();
                    assertThat(c.details()).contains("no sandbox");
                });
        assertThat(scans).isEmpty();
        assertThat(review.markdown()).contains("Dependency inventory").contains("com.acme:edge:RELEASE");

        SecurityReview greenfield = (SecurityReview) ((StageResult.Success) new SecurityReviewAgent(runner)
                .execute(Contexts.of(AgentRole.SECURITY_REVIEW, "req", design, changeSet))).payload();
        assertThat(greenfield.dependencies()).isEmpty();
        assertThat(greenfield.checks()).extracting(Check::name).doesNotContain(DependencyReview.INVENTORY_CHECK,
                DependencyReview.SCAN_CHECK);
    }
}
