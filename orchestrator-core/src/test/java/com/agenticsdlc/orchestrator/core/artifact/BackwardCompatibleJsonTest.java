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
}
