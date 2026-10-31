package com.agenticsdlc.orchestrator.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.llm.JsonSchemas;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

/**
 * Records that gained fields still read the JSON persisted before the fields existed.
 */
class BackwardCompatibleJsonTest {

    private final JsonCodec codec = JsonCodec.standard();

    @Test
    void provenanceWithoutCacheCountsAndCostReads() {
        Provenance old = codec.fromJson("""
                {"agentId":"anthropic:claude-opus-5-5","agentRole":"ARCHITECTURE","attempt":1,"derivedFrom":{},
                 "inputTokens":1200,"latencyMs":40,"model":"claude-opus-5-5","outputTokens":300,"planVersion":1,
                 "promptHash":"abc","providerId":"anthropic"}""", Provenance.class);
        assertThat(old.cacheReadTokens()).isZero();
        assertThat(old.cacheCreationTokens()).isZero();
        assertThat(old.costMicroUsd()).as("unknown").isNull();

        Provenance current = new Provenance(Map.of("intake", 1), AgentRole.ARCHITECTURE, "anthropic:m", "anthropic",
                "m", "h", 10, 2, 5, 1, 1, 4L, 3L, 120L);
        assertThat(codec.fromJson(codec.toJson(current), Provenance.class)).isEqualTo(current);
        assertThat(new Provenance(Map.of(), AgentRole.PLANNING, "deterministic", "deterministic", null, null, 0, 0, 3,
                1, 1).costMicroUsd()).as("deterministic provenance costs nothing").isZero();
    }

    @Test
    void securityReviewWithoutDependenciesReadsAndTheSchemaAllowsAnEmptyInventory() {
        SecurityReview old = codec.fromJson("""
                {"checks":[{"name":"input-validation-in-design","passed":true,"details":"ok"}],"complianceNotes":[],
                 "findings":[],"passed":true,"threats":[]}""", SecurityReview.class);
        assertThat(old.dependencies()).isEmpty();
        old.validate();

        Map<String, Object> schema = JsonSchemas.forRecord(SecurityReview.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("dependencies");
        assertThat(properties.get("dependencies")).asString().contains("array").contains("artifactId")
                .contains("DEPENDENCY").contains("PLUGIN");

        SecurityReview withInventory = new SecurityReview(List.of(), old.checks(), List.of(), List.of(), true,
                List.of(new DependencyRecord("pom.xml", "g", "a", "1", "compile", DependencyRecord.Kind.DEPENDENCY)));
        assertThat(codec.fromJson(codec.toJson(withInventory), SecurityReview.class)).isEqualTo(withInventory);
        assertThat(codec.toJson(withInventory)).doesNotContain("coordinate");
        assertThat(withInventory.dependencies().getFirst().section()).isEqualTo("dependencies");
        assertThat(new DependencyRecord("p", "g", "a", "1", "build", DependencyRecord.Kind.PLUGIN).section())
                .isEqualTo("build/plugins");
    }

    @Test
    void changeSetsDesignComponentsAndImpactAnalysesWithoutTheNewFieldsRead() {
        ChangeSet changeSet = codec.fromJson("""
                {"baseRevision":"HEAD","buildCommand":"./mvnw verify","changes":[{"content":"x","contentKind":"FULL",
                 "language":"java","operation":"CREATE","path":"url-shortener/A.java","summary":"s","traces":[]}],
                 "risks":[],"rollbackPlan":"revert","traces":["T1"]}""", ChangeSet.class);
        assertThat(changeSet.requestedSources()).isEmpty();
        assertThat(changeSet).isEqualTo(new ChangeSet("HEAD", changeSet.changes(), "./mvnw verify", List.of(),
                "revert", List.of("T1")));
        ChangeSet requesting = new ChangeSet("HEAD", changeSet.changes(), "./mvnw verify", List.of(), "revert",
                List.of("T1"), List.of("url-shortener/src/main/java/a/Link.java"));
        assertThat(codec.fromJson(codec.toJson(requesting), ChangeSet.class)).isEqualTo(requesting);
        assertThat(requesting.markdown()).contains("Requested sources (for the next attempt)")
                .contains("url-shortener/src/main/java/a/Link.java");
        assertThat(changeSet.markdown()).doesNotContain("Requested sources");

        DesignSpec.Component component = codec.fromJson("""
                {"isNew":false,"name":"Link entity","responsibility":"stores the label","type":"ENTITY"}""",
                DesignSpec.Component.class);
        assertThat(component.path()).isEmpty();
        assertThat(component).isEqualTo(new DesignSpec.Component("Link entity", "stores the label", "ENTITY", false));
        assertThat(component.withPath("a/Link.java").path()).isEqualTo("a/Link.java");

        ImpactAnalysis impact = codec.fromJson("""
                {"components":[],"dataFlows":[],"endpoints":[],"entities":[],"existingCodebase":false,"indexedFiles":0,
                 "migrations":[],"riskFactors":[],"suggestedTests":[],"summary":"s","workspaceRoot":"none"}""",
                ImpactAnalysis.class);
        assertThat(impact.reconciliation()).isEmpty();
        assertThat(impact.markdown()).doesNotContain("Reconciliation");
        ImpactAnalysis reconciled = new ImpactAnalysis("ws", 1, true, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "s", List.of("merged from static analysis: Link"));
        assertThat(codec.fromJson(codec.toJson(reconciled), ImpactAnalysis.class)).isEqualTo(reconciled);
        assertThat(reconciled.markdown()).contains("### Reconciliation with static analysis")
                .contains("merged from static analysis: Link");
    }

    @Test
    void theSchemaDescribesRequestedSourcesAndThePathsOfComponents() {
        assertThat(property(JsonSchemas.forRecord(ChangeSet.class), "requestedSources"))
                .containsEntry("type", "array").containsEntry("description", "Paths from the index that must be "
                        + "shown in the next attempt because they are required to write correct diffs.");
        Map<String, Object> component = items(property(JsonSchemas.forRecord(ImpactAnalysis.class), "components"));
        assertThat(property(component, "path")).containsEntry("type", "string")
                .extractingByKey("description").asString().contains("codebase index")
                .contains("May be empty only for a NEW component");
        assertThat(property(JsonSchemas.forRecord(ImpactAnalysis.class), "reconciliation"))
                .extractingByKey("description").asString().startsWith("Leave empty");
        Map<String, Object> designComponent = items(property(JsonSchemas.forRecord(DesignSpec.class),
                "components"));
        assertThat(property(designComponent, "path")).containsEntry("type", "string")
                .extractingByKey("description").asString().contains("Empty for a new component");
        assertThat(String.valueOf(designComponent.get("required"))).contains("path").contains("isNew");
        assertThat(property(JsonSchemas.forRecord(ChangeSet.class), "buildCommand")).doesNotContainKey("description");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> items(Map<String, Object> arraySchema) {
        return (Map<String, Object>) arraySchema.get("items");
    }
}
