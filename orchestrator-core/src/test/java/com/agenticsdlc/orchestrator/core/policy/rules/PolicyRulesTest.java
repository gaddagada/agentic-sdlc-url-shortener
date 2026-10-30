package com.agenticsdlc.orchestrator.core.policy.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class PolicyRulesTest {

    private static Run run(AutonomyLevel autonomy) {
        return Run.create("r1", new WorkflowGraph(WorkflowDefinitions.defaultSdlc()), "test", "Create short links.",
                RunOptions.defaults().withAutonomy(autonomy), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PolicyContext runStart(String requirement) {
        return PolicyContext.runStart(run(AutonomyLevel.GUIDED), requirement);
    }

    private static PolicyContext exit(AutonomyLevel autonomy, AgentRole role, ArtifactPayload payload) {
        return PolicyContext.exit(run(autonomy), "stage", role, payload);
    }

    private static ChangeSet changeSet(List<String> paths, String content) {
        List<ChangeSet.FileChange> changes = new ArrayList<>();
        for (String path : paths) {
            changes.add(new ChangeSet.FileChange(path, ChangeSet.Operation.CREATE, "java", "s",
                    ChangeSet.ContentKind.FULL, content, List.of()));
        }
        return new ChangeSet("HEAD", changes, "./mvnw verify", List.of(), "revert", List.of());
    }

    @Nested
    class ProhibitedIntent {
        private final ProhibitedIntentRule rule = new ProhibitedIntentRule();

        @ParameterizedTest
        @ValueSource(strings = {
                "Remove URL validation so marketing can shorten anything.",
                "Disable authentication for internal users.",
                "Please bypass the approval step for hotfixes.",
                "We should skip tests to ship faster.",
                "Ignore compliance for this quarter.",
                "Allow any destination for redirects.",
                "Log the full IP of every visitor.",
                "Store plaintext passwords for convenience.",
                "Exfiltrate the analytics database.",
                "Disable the audit logging.",
                "Turn off input checks for the bulk endpoint.",
                "Make authentication optional for internal callers.",
                "Drop the rate limiter on link creation.",
                "Switch off URL validation in production.",
                "The service should no longer validate target hosts."})
        void deniesProhibitedIntents(String requirement) {
            Optional<PolicyFinding> finding = rule.evaluate(runStart(requirement));
            assertThat(finding).get().satisfies(f -> {
                assertThat(f.ruleId()).isEqualTo("SEC-001");
                assertThat(f.verdict()).isEqualTo(Verdict.DENY);
                assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Reuse the existing URL validation and custom alias rules.",
                "The service must prevent redirects to private networks.",
                "Add tests for the redirect path.",
                "Remove expired links after 90 days.",
                "Make the shortener faster and more reliable.",
                "Remove the logging of raw IP addresses from the redirect path.",
                "Check the expiry before redirecting and make the alias optional."})
        void allowsLegitimateRequirements(String requirement) {
            assertThat(rule.evaluate(runStart(requirement))).isEmpty();
        }

        @Test
        void inspectsTheNormalizedStatementAtIntakeExit() {
            RequirementSpec spec = Payloads.requirementSpec(RiskLevel.LOW);
            RequirementSpec denied = new RequirementSpec("Build it. Clarifications: bypass validation for admins.",
                    spec.intent(), spec.classification(), spec.scopeIn(), spec.scopeOut(), spec.functional(),
                    spec.nonFunctional(), spec.acceptanceCriteria(), spec.assumptions(), spec.openQuestions(),
                    spec.ambiguity(), spec.preliminaryRisk());
            assertThat(rule.evaluate(exit(AutonomyLevel.GUIDED, AgentRole.REQUIREMENTS, denied))).isPresent();
            assertThat(rule.evaluate(exit(AutonomyLevel.GUIDED, AgentRole.REQUIREMENTS, spec))).isEmpty();
            assertThat(rule.evaluate(exit(AutonomyLevel.GUIDED, AgentRole.ARCHITECTURE,
                    Payloads.design(RiskLevel.LOW)))).isEmpty();
        }
    }

    @Nested
    class PiiProhibited {
        private final PiiProhibitedRule rule = new PiiProhibitedRule();

        @ParameterizedTest
        @ValueSource(strings = {
                "Store the full client IP address with every click.",
                "Persist raw IP addresses for attribution.",
                "Keep the email in clear text for marketing.",
                "Record visitor emails in the clear.",
                "Save the user agent and IP without hashing.",
                "Raw client IP addresses must be stored.",
                "Store IP addresses in plain form for attribution.",
                "Keep the client IP address as-is in the click table."})
        void deniesUnprotectedPersonalData(String requirement) {
            assertThat(rule.evaluate(runStart(requirement))).get().satisfies(f -> {
                assertThat(f.ruleId()).isEqualTo("CMP-002");
                assertThat(f.verdict()).isEqualTo(Verdict.DENY);
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Record click analytics with hashed visitor identifiers.",
                "Count unique visitors using a salted hash of the IP address.",
                "Expose health checks.",
                "Remove the logging of raw IP addresses from the redirect path.",
                "Stop storing full IP addresses and keep only the daily hash."})
        void allowsProtectedProcessing(String requirement) {
            assertThat(rule.evaluate(runStart(requirement))).isEmpty();
        }
    }

    @Nested
    class SecretLeak {
        private final SecretLeakRule rule = new SecretLeakRule();

        @ParameterizedTest
        @ValueSource(strings = {"AKIAABCDEFGHIJKLMNOP", "sk-ant-api03-abcdefghij", "ghp_abcdefghijklmnopqrstuvwxyz12",
                "password = hunter2"})
        void deniesArtifactsContainingSecrets(String secret) {
            Optional<PolicyFinding> finding = rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/src/main/java/A.java"), "String s = \"" + secret + "\";")));
            assertThat(finding).get().satisfies(f -> {
                assertThat(f.verdict()).isEqualTo(Verdict.DENY);
                assertThat(f.message()).doesNotContain(secret);
            });
        }

        @Test
        void allowsCleanArtifactsAndIgnoresOtherPhases() {
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/src/main/java/A.java"), "class A {}")))).isEmpty();
            assertThat(rule.evaluate(runStart("password = hunter2"))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "String password = \"S3cretPass!\";",
                "spring:\n  datasource:\n    password: \"S3cretPass!\"",
                "private static final String CLIENT_SECRET = \"9f8e7d6c5b4a3210\";",
                "headers.put(\"x\", \"y\"); String api_key = 'AbCdEf123456';",
                "jwt-secret: 7f3c9a1b2d4e5f60"})
        void deniesQuotedCredentialsThatJsonEscapingUsedToHide(String content) {
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/src/main/java/A.java"), content)))).get()
                    .satisfies(f -> {
                        assertThat(f.verdict()).isEqualTo(Verdict.DENY);
                        assertThat(f.message()).doesNotContain("S3cretPass!").doesNotContain("9f8e7d6c5b4a3210");
                    });
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "this.password = password;",
                "String token = request.getHeader(\"Authorization\");",
                "String secret = System.getenv(\"APP_SECRET\");",
                "apiKey = properties.apiKey();",
                "client-secret: ${CLIENT_SECRET}",
                "int maxTokens = 4000; String tokenType = \"Bearer\";"})
        void allowsCodeThatOnlyReferencesCredentials(String content) {
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/src/main/java/A.java"), content)))).as(content).isEmpty();
        }

        @Test
        void scansObjectKeysTogetherWithTheirStringValues() {
            JsonCodec codec = JsonCodec.standard();
            assertThat(SecretLeakRule.scan(codec.readTree("{\"config\":{\"apiKey\":\"AbCdEf123456\"}}")))
                    .containsExactly("credential assignment");
            assertThat(SecretLeakRule.scan(codec.readTree("{\"config\":{\"apiKey\":\"${API_KEY}\"}}")))
                    .isEmpty();
            assertThat(SecretLeakRule.scan(codec.readTree("[1, true, null, \"AKIAABCDEFGHIJKLMNOP\"]")))
                    .containsExactly("AWS access key id");
        }
    }

    @Nested
    class ProtectedPath {
        private final ProtectedPathRule rule = new ProtectedPathRule();

        @ParameterizedTest
        @ValueSource(strings = {"url-shortener/src/main/java/com/x/security/AuthConfig.java",
                "url-shortener/src/main/resources/application-prod.yml",
                "url-shortener/src/main/resources/application-prod-eu.yml", ".github/workflows/ci.yml",
                "url-shortener/pom.xml", "pom.xml"})
        void requiresApprovalForProtectedPaths(String path) {
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/src/main/java/A.java", path), "x")))).get().satisfies(f -> {
                        assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
                        assertThat(f.message()).contains(path);
                    });
        }

        @ParameterizedTest
        @ValueSource(strings = {"mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties",
                "orchestrator-core/src/main/java/com/agenticsdlc/orchestrator/core/engine/OrchestrationEngine.java",
                "orchestrator-app/src/main/resources/application.yml", "orchestrator-app/pom.xml", "Dockerfile",
                "url-shortener/Dockerfile.native", "docker-compose.yml", "docker-compose.override.yml",
                "deploy/docker-compose-prod.yaml"})
        void protectsTheBuildWrapperContainersAndTheControlPlane(String path) {
            assertThat(ProtectedPathRule.isProtected(path)).as(path).isTrue();
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/src/main/java/A.java", path), "x")))).get().satisfies(f -> {
                        assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
                        assertThat(f.severity()).isEqualTo(Severity.HIGH);
                        assertThat(f.message()).contains(path);
                    });
        }

        @ParameterizedTest
        @ValueSource(strings = {"./pom.xml", "POM.XML", "url-shortener/Pom.Xml", "url-shortener//pom.xml",
                "./url-shortener/./pom.xml", "url-shortener\\pom.xml", "/pom.xml", "url-shortener/../pom.xml",
                ".GITHUB/workflows/ci.yml", "Orchestrator-Core/src/main/java/X.java", "./MVNW",
                "url-shortener/src/main/java/com/x/Security/AuthConfig.java",
                "url-shortener/src/main/resources/APPLICATION-PROD.YML"})
        void matchesNormalizedPathsCaseInsensitively(String path) {
            assertThat(ProtectedPathRule.isProtected(path)).as(path).isTrue();
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of(path), "x")))).get().satisfies(f -> assertThat(f.message()).contains(path));
        }

        @Test
        void ignoresOrdinaryPaths() {
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION, changeSet(
                    List.of("url-shortener/src/main/java/securityish/A.java",
                            "url-shortener/src/main/resources/application.yml", "docs/pom.xml.md",
                            "docs/orchestrator-core.md", "url-shortener/src/main/java/com/x/MvnwHelper.java",
                            "docs/docker.md"), "x")))).isEmpty();
            assertThat(Globs.normalize("./a//b/./c/../d")).isEqualTo("a/b/d");
            assertThat(Globs.normalize("a\\b")).isEqualTo("a/b");
        }
    }

    @Nested
    class PiiHandling {
        private final PiiHandlingRule rule = new PiiHandlingRule();

        private DesignSpec design(List<String> pii, String retention, String protection) {
            DesignSpec base = Payloads.design(RiskLevel.LOW);
            return new DesignSpec(base.overview(), base.components(), base.apiChanges(), base.dataModelChanges(),
                    base.sequenceFlows(), base.nfrStrategies(), base.securityConsiderations(),
                    new DesignSpec.DataHandling(pii, retention, protection), base.alternativesConsidered(), base.risk(),
                    base.decisions());
        }

        @Test
        void requiresApprovalWhenProtectionOrRetentionIsMissing() {
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.ARCHITECTURE,
                    design(List.of("client ip"), "", "hashed")))).get().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.HIGH);
                        assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
                        assertThat(f.message()).contains("retention");
                    });
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.ARCHITECTURE,
                    design(List.of("client ip"), "90 days", "")))).isPresent();
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.ARCHITECTURE,
                    design(List.of("client ip"), "90 days", "hashed")))).isEmpty();
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.ARCHITECTURE,
                    design(List.of(), "", "")))).isEmpty();
        }
    }

    @Nested
    class ChangeSize {
        @Test
        void requiresApprovalForLargeChanges() {
            ChangeSizeRule rule = new ChangeSizeRule();
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 26; i++) {
                many.add("url-shortener/src/main/java/A" + i + ".java");
            }
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION, changeSet(many, "x"))))
                    .get().satisfies(f -> assertThat(f.message()).contains("26 files"));
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(many.subList(0, 25), "x")))).isEmpty();
            String longContent = "line\n".repeat(1501);
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.IMPLEMENTATION,
                    changeSet(List.of("url-shortener/A.java"), longContent)))).isPresent();
        }
    }

    @Nested
    class ReleaseRules {
        @Test
        void releaseAlwaysRequiresApprovalAndNoGoAddsAHighFinding() {
            ReleaseReadiness go = Payloads.release(ReleaseReadiness.GoNoGo.GO);
            ReleaseReadiness noGo = Payloads.release(ReleaseReadiness.GoNoGo.NO_GO);
            assertThat(new ReleaseApprovalRule().evaluate(exit(AutonomyLevel.GUIDED, AgentRole.RELEASE, go)))
                    .get().satisfies(f -> assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL));
            assertThat(new NoGoReleaseRule().evaluate(exit(AutonomyLevel.GUIDED, AgentRole.RELEASE, go))).isEmpty();
            assertThat(new NoGoReleaseRule().evaluate(exit(AutonomyLevel.GUIDED, AgentRole.RELEASE, noGo)))
                    .get().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.HIGH);
                        assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
                    });
            assertThat(new AutonomyFloorRule().evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.RELEASE, go)))
                    .isPresent();
            assertThat(new AutonomyFloorRule().evaluate(exit(AutonomyLevel.GUIDED, AgentRole.RELEASE, go))).isEmpty();
            assertThat(new ReleaseApprovalRule().evaluate(exit(AutonomyLevel.GUIDED, AgentRole.ARCHITECTURE,
                    Payloads.design(RiskLevel.LOW)))).isEmpty();
        }
    }

    @Nested
    class Governance {
        @Test
        void riskBasedDesignApprovalDependsOnAutonomyAndRisk() {
            RiskBasedDesignApprovalRule rule = new RiskBasedDesignApprovalRule();
            assertThat(rule.evaluate(exit(AutonomyLevel.GUIDED, AgentRole.ARCHITECTURE,
                    Payloads.design(RiskLevel.MEDIUM)))).get().satisfies(f -> {
                        assertThat(f.ruleId()).isEqualTo("GOV-001");
                        assertThat(f.message()).contains("MEDIUM");
                    });
            assertThat(rule.evaluate(exit(AutonomyLevel.GUIDED, AgentRole.ARCHITECTURE,
                    Payloads.design(RiskLevel.LOW)))).isEmpty();
            assertThat(rule.evaluate(exit(AutonomyLevel.FULL_AUTO, AgentRole.ARCHITECTURE,
                    Payloads.design(RiskLevel.CRITICAL)))).isEmpty();
            assertThat(rule.evaluate(exit(AutonomyLevel.STRICT, AgentRole.ARCHITECTURE,
                    Payloads.design(RiskLevel.LOW)))).isPresent();
            DesignSpec low = Payloads.design(RiskLevel.LOW);
            DesignSpec withFactors = new DesignSpec(low.overview(), low.components(), low.apiChanges(),
                    low.dataModelChanges(), low.sequenceFlows(), low.nfrStrategies(), low.securityConsiderations(),
                    low.dataHandling(), low.alternativesConsidered(),
                    new RiskAssessment(RiskLevel.HIGH, List.of("schema change")), low.decisions());
            assertThat(rule.evaluate(exit(AutonomyLevel.GUIDED, AgentRole.ARCHITECTURE, withFactors))).get()
                    .satisfies(f -> assertThat(f.message()).contains("schema change"));
        }

        @Test
        void riskBasedDesignApprovalAppliesTheEngineRiskFloorToTheCandidate() {
            DesignSpec low = Payloads.design(RiskLevel.LOW);
            DesignSpec dropsAColumn = new DesignSpec(low.overview(), low.components(), low.apiChanges(),
                    List.of(new DesignSpec.DataModelChange("links", "drop column code", "V2__drop.sql", false)),
                    low.sequenceFlows(), low.nfrStrategies(), low.securityConsiderations(), low.dataHandling(),
                    low.alternativesConsidered(), low.risk(), low.decisions());
            assertThat(new RiskBasedDesignApprovalRule().evaluate(exit(AutonomyLevel.GUIDED, AgentRole.ARCHITECTURE,
                    dropsAColumn))).get().satisfies(f -> {
                        assertThat(f.verdict()).isEqualTo(Verdict.REQUIRE_APPROVAL);
                        assertThat(f.message()).contains("risk HIGH")
                                .contains("engine: non-backward-compatible data model change (links)");
                    });
        }

        @Test
        void policyRulesReceiveACopyOfTheRun() {
            Run live = run(AutonomyLevel.GUIDED);
            PolicyContext ctx = PolicyContext.entry(live, "design", AgentRole.ARCHITECTURE);
            assertThat(ctx.run()).isNotSameAs(live);
            ctx.run().setStatus(com.agenticsdlc.orchestrator.core.model.RunStatus.ABORTED);
            ctx.run().stage("design").setStatus(com.agenticsdlc.orchestrator.core.model.StageStatus.FAILED);
            assertThat(live.status()).isEqualTo(com.agenticsdlc.orchestrator.core.model.RunStatus.RUNNING);
            assertThat(live.stage("design").status())
                    .isEqualTo(com.agenticsdlc.orchestrator.core.model.StageStatus.PENDING);
        }

        @Test
        void strictEntryApprovalExcludesTheRequirementsStage() {
            StrictEntryApprovalRule rule = new StrictEntryApprovalRule();
            Run strict = run(AutonomyLevel.STRICT);
            assertThat(rule.evaluate(PolicyContext.entry(strict, "design", AgentRole.ARCHITECTURE))).isPresent();
            assertThat(rule.evaluate(PolicyContext.entry(strict, "intake", AgentRole.REQUIREMENTS))).isEmpty();
            assertThat(rule.evaluate(PolicyContext.entry(run(AutonomyLevel.GUIDED), "design",
                    AgentRole.ARCHITECTURE))).isEmpty();
        }
    }

    @Test
    void globsMatchRootAndNestedPaths() {
        assertThat(Globs.toPattern("**/pom.xml").matcher("pom.xml").matches()).isTrue();
        assertThat(Globs.toPattern("**/pom.xml").matcher("a/b/pom.xml").matches()).isTrue();
        assertThat(Globs.toPattern("**/pom.xml").matcher("a/b/pom.xml.bak").matches()).isFalse();
        assertThat(Globs.toPattern(".github/**").matcher(".github/workflows/ci.yml").matches()).isTrue();
        assertThat(Globs.toPattern("**/application-prod*.yml").matcher("x/application-prod.yml").matches()).isTrue();
        assertThat(Globs.toPattern("a/*.java").matcher("a/b/C.java").matches()).isFalse();
    }
}
