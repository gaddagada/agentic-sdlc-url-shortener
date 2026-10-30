package com.agenticsdlc.orchestrator.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackException;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackLoader;

/**
 * {@link PolicyPackFiles}: the shipped example YAML pack is the core's sample pack (same rules, same hash), YAML and
 * JSON files and classpath resources load, and every problem of an invalid pack is reported.
 */
class PolicyPackFilesTest {

    @TempDir
    private Path dir;

    @Test
    void theExampleYamlPackIsTheCoreSamplePack() {
        PolicyPack example = PolicyPackFiles.load("classpath:policy/example-policy-pack.yaml");
        PolicyPack sample = PolicyPackLoader.sample();

        assertThat(example).isEqualTo(sample);
        assertThat(example.hash()).isEqualTo(sample.hash());
        assertThat(example.rules()).extracting(r -> r.id()).containsExactly("ORG-001", "ORG-002", "ORG-003",
                "ORG-004");
        assertThat(PolicyEngine.withDefaultRulesAnd(example).pack()).isEqualTo(example);
    }

    @Test
    void jsonAndYamlFilesLoadFromTheFileSystem() throws IOException {
        Path json = dir.resolve("pack.json");
        Files.writeString(json, JsonCodec.standard().toJson(PolicyPackLoader.sample()), StandardCharsets.UTF_8);
        Path yaml = dir.resolve("pack.YML");
        Files.writeString(yaml, """
                name: tiny
                version: "2"
                disabledRules: [CHG-001]
                rules:
                  - id: ACME-001
                    phase: RUN_START
                    when: {type: intentMatches, regexes: ["(?i)crypto"]}
                    effect: WARN
                    message: crypto work needs the security team
                """, StandardCharsets.UTF_8);

        assertThat(PolicyPackFiles.load(json.toString())).isEqualTo(PolicyPackLoader.sample());
        PolicyPack tiny = PolicyPackFiles.load(yaml.toString());
        assertThat(tiny.name()).isEqualTo("tiny");
        assertThat(tiny.version()).isEqualTo("2");
        assertThat(tiny.disabledRules()).containsExactly("CHG-001");
    }

    @Test
    void everyProblemOfAnInvalidPackIsReported() {
        PolicyPackException error = (PolicyPackException) org.assertj.core.api.Assertions.catchThrowable(() ->
                PolicyPackFiles.parse("""
                        name: broken
                        version: 1.0
                        disabledRules: [SEC-002]
                        rules:
                          - id: ORG-001
                            phase: EXIT
                            when: {type: payloadMatches, regexes: ["(unclosed"]}
                            effect: DENY
                            message: bad regex
                          - id: ORG-002
                            phase: EXIT
                            when: {type: riskAtLeast, level: HIGH}
                            effect: WARN
                            message: unknown field
                            colour: red
                        """, true));

        assertThat(error.problems()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(String.join("\n", error.problems())).contains("$.version", "SEC-002",
                "$.rules[0].when.regexes[0]", "$.rules[1].colour");
    }

    @Test
    void malformedYamlAndMissingFilesAreClearErrors() {
        assertThatThrownBy(() -> PolicyPackFiles.parse("name: [unclosed", true))
                .isInstanceOf(PolicyPackException.class).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> PolicyPackFiles.parse("", true))
                .isInstanceOf(PolicyPackException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> PolicyPackFiles.parse("name: a\nname: b\nversion: '1'\n", true))
                .as("duplicate keys").isInstanceOf(PolicyPackException.class);
        assertThatThrownBy(() -> PolicyPackFiles.load(dir.resolve("absent.yaml").toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("file not found");
        assertThatThrownBy(() -> PolicyPackFiles.load("classpath:policy/absent.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("classpath resource not found");
    }
}
