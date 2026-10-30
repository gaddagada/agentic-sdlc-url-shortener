package com.agenticsdlc.orchestrator.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.Payloads;

class RiskFloorTest {

    private static DesignSpec design(List<DesignSpec.ApiChange> api, List<DesignSpec.DataModelChange> data,
                                     List<String> pii) {
        DesignSpec base = Payloads.design(RiskLevel.LOW);
        return new DesignSpec(base.overview(), base.components(), api, data, base.sequenceFlows(),
                base.nfrStrategies(), base.securityConsiderations(), new DesignSpec.DataHandling(pii, "30 days",
                "hashing"), base.alternativesConsidered(), base.risk(), base.decisions());
    }

    private static ImpactAnalysis impact(ImpactAnalysis.ImpactedComponent... components) {
        return new ImpactAnalysis("repo", 10, true, List.of(components), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "summary");
    }

    private static ImpactAnalysis.ImpactedComponent component(String name, ImpactAnalysis.ComponentType type,
                                                              String path, ImpactAnalysis.ChangeNature nature) {
        return new ImpactAnalysis.ImpactedComponent(name, type, path, 0.9, List.of("reason"), nature);
    }

    private static DesignSpec.ApiChange api(boolean breaking) {
        return new DesignSpec.ApiChange("POST", "/api/v1/links", "create", "url", "link", List.of("400"), breaking);
    }

    private static DesignSpec.DataModelChange data(boolean backwardCompatible) {
        return new DesignSpec.DataModelChange("links", "add column alias", "V2__alias.sql", backwardCompatible);
    }

    @Test
    void nothingRaisesTheFloorForAPlainDesign() {
        assertThat(RiskFloor.assess(Payloads.design(RiskLevel.LOW), Payloads.impact())).isEqualTo(RiskAssessment.low());
        assertThat(RiskFloor.assess(null, null)).isEqualTo(RiskAssessment.low());
        assertThat(RiskFloor.assess(null, impact(component("LinkTest", ImpactAnalysis.ComponentType.TEST,
                "src/test/java/LinkTest.java", ImpactAnalysis.ChangeNature.REGRESSION_RISK))).level())
                .isEqualTo(RiskLevel.LOW);
    }

    @Test
    void additiveChangesAndModifiedComponentsAreMedium() {
        assertThat(RiskFloor.assess(design(List.of(api(false)), List.of(), List.of()), null))
                .satisfies(r -> {
                    assertThat(r.level()).isEqualTo(RiskLevel.MEDIUM);
                    assertThat(r.factors()).containsExactly("engine: API change (1)");
                });
        assertThat(RiskFloor.assess(design(List.of(), List.of(data(true)), List.of()), null).level())
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskFloor.assess(null, impact(component("LinkService", ImpactAnalysis.ComponentType.SERVICE,
                "src/main/java/app/LinkService.java", ImpactAnalysis.ChangeNature.EXTEND))).factors())
                .containsExactly("engine: existing component changed (LinkService)");
    }

    @Test
    void breakingIncompatiblePersonalDataAndSensitiveComponentsAreHigh() {
        assertThat(RiskFloor.assess(design(List.of(api(true)), List.of(), List.of()), null).factors())
                .containsExactly("engine: breaking API change (POST /api/v1/links)");
        assertThat(RiskFloor.assess(design(List.of(), List.of(data(false)), List.of()), null).factors())
                .containsExactly("engine: non-backward-compatible data model change (links)");
        assertThat(RiskFloor.assess(design(List.of(), List.of(), List.of("ip")), null).factors())
                .containsExactly("engine: personal data processed (ip)");
        assertThat(RiskFloor.assess(null, impact(component("RateLimitFilter", ImpactAnalysis.ComponentType.FILTER,
                "src/main/java/app/RateLimitFilter.java", ImpactAnalysis.ChangeNature.REGRESSION_RISK))).level())
                .isEqualTo(RiskLevel.HIGH);
        assertThat(RiskFloor.assess(null, impact(component("AppConfig", ImpactAnalysis.ComponentType.CONFIG,
                "src/main/java/app/AppConfig.java", ImpactAnalysis.ChangeNature.NEW))).level())
                .isEqualTo(RiskLevel.HIGH);
        assertThat(RiskFloor.assess(null, impact(component("ApiKeys", ImpactAnalysis.ComponentType.SERVICE,
                "src/main/java/app/security/ApiKeys.java", ImpactAnalysis.ChangeNature.MODIFY))).factors())
                .containsExactly("engine: impacted security component (ApiKeys)");
    }

    @Test
    void theRunRiskIsTheHigherOfTheAgentRiskAndTheFloor() {
        Run run = Run.create("r", new WorkflowGraph(WorkflowDefinitions.defaultSdlc()), "s", "req",
                RunOptions.defaults(), Instant.parse("2026-01-01T00:00:00Z"));
        Artifact design = Contexts.artifact(design(List.of(), List.of(data(false)), List.of()));
        run.addArtifact(design);
        run.accept(design.stageId(), design.version());
        assertThat(design.provenance().agentRole()).isEqualTo(AgentRole.ARCHITECTURE);

        RiskAssessment risk = run.currentRisk();
        assertThat(risk.level()).as("the agent reported LOW").isEqualTo(RiskLevel.HIGH);
        assertThat(risk.factors()).containsExactly("engine: non-backward-compatible data model change (links)");
    }
}
