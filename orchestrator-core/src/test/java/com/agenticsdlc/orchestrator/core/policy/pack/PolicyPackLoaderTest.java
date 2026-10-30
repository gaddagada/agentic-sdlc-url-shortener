package com.agenticsdlc.orchestrator.core.policy.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;

class PolicyPackLoaderTest {

    private static final String RULE = """
            {"id": "ORG-001", "description": "security", "phase": "EXIT", "stages": ["implement"],
             "when": {"type": "pathMatches", "globs": ["**/security/**"]},
             "effect": "REQUIRE_APPROVAL", "severity": "HIGH", "message": "touches security code"}""";

    private static String pack(String rules) {
        return pack(rules, "[]");
    }

    private static String pack(String rules, String disabled) {
        return "{\"name\": \"acme\", \"version\": \"1.0.0\", \"disabledRules\": " + disabled + ", \"rules\": [" + rules
                + "]}";
    }

    private static String rule(String phase, String when, String effect, String extra) {
        return "{\"id\": \"ORG-001\", \"phase\": \"" + phase + "\", \"when\": " + when + ", \"effect\": \"" + effect
                + "\", \"message\": \"m\"" + extra + "}";
    }

    private static List<String> problems(String json) {
        try {
            PolicyPackLoader.fromJson(json);
        } catch (PolicyPackException e) {
            assertThat(e.getMessage()).startsWith("invalid policy pack: ");
            return e.problems();
        }
        throw new AssertionError("expected the pack to be rejected: " + json);
    }

    @Test
    void loadsAValidPackWithEveryConditionType() {
        String when = """
                {"type": "anyOf", "conditions": [
                  {"type": "intentMatches", "regexes": ["\\\\bPII\\\\b"]},
                  {"type": "pathMatches", "globs": ["**/db/**"]},
                  {"type": "payloadMatches", "regexes": ["secret"]},
                  {"type": "riskAtLeast", "level": "high"},
                  {"type": "changeSizeOver", "files": 10, "lines": 500},
                  {"type": "releaseDecisionIs", "decision": "NO_GO"},
                  {"type": "autonomyIs", "autonomy": "FULL_AUTO"},
                  {"type": "allOf", "conditions": [{"type": "not", "condition": {"type": "autonomyIs",
                    "autonomy": "STRICT"}}]}]}""";
        PolicyPack pack = PolicyPackLoader.fromJson(pack(rule("exit", when, "deny", ""), "[\"CHG-001\"]"));

        assertThat(pack.name()).isEqualTo("acme");
        assertThat(pack.version()).isEqualTo("1.0.0");
        assertThat(pack.disabledRules()).containsExactly("CHG-001");
        DeclarativeRule rule = pack.rules().getFirst();
        assertThat(rule.phase()).isEqualTo(GatePhase.EXIT);
        assertThat(rule.effect()).isEqualTo(Effect.DENY);
        assertThat(rule.severity()).as("default severity of DENY").isEqualTo(Severity.HIGH);
        assertThat(rule.stages()).isEmpty();
        assertThat(rule.when()).isEqualTo(new Condition.AnyOf(List.of(
                new Condition.IntentMatches(List.of("\\bPII\\b")),
                new Condition.PathMatches(List.of("**/db/**")),
                new Condition.PayloadMatches(List.of("secret")),
                new Condition.RiskAtLeast(RiskLevel.HIGH),
                new Condition.ChangeSizeOver(10, 500),
                new Condition.ReleaseDecisionIs(ReleaseReadiness.GoNoGo.NO_GO),
                new Condition.AutonomyIs(AutonomyLevel.FULL_AUTO),
                new Condition.AllOf(List.of(new Condition.Not(new Condition.AutonomyIs(AutonomyLevel.STRICT)))))));
    }

    @Test
    void roundTripsThroughJsonAndHashesCanonically() {
        PolicyPack pack = PolicyPackLoader.fromJson(pack(RULE));
        String json = JsonCodec.standard().toJson(pack);
        assertThat(json).contains("\"type\":\"pathMatches\"").doesNotContain("\"hash\"");

        assertThat(PolicyPackLoader.fromJson(json)).isEqualTo(pack);
        assertThat(JsonCodec.standard().fromJson(json, PolicyPack.class)).isEqualTo(pack);
        assertThat(pack.hash()).hasSize(64).isEqualTo(PolicyPackLoader.fromJson(json).hash());

        String reordered = """
                {"rules": [{"message": "touches security code", "severity": "HIGH", "effect": "REQUIRE_APPROVAL",
                  "when": {"globs": ["**/security/**"], "type": "pathMatches"}, "stages": ["implement"],
                  "phase": "EXIT", "description": "security", "id": "ORG-001"}],
                 "disabledRules": [], "version": "1.0.0", "name": "acme"}""";
        assertThat(PolicyPackLoader.fromJson(reordered).hash()).isEqualTo(pack.hash());
        assertThat(PolicyPackLoader.fromJson(pack(RULE.replace("touches security code", "other"))).hash())
                .isNotEqualTo(pack.hash());
    }

    @Test
    void severityAndOptionalFieldsDefault() {
        PolicyPack pack = PolicyPackLoader.fromJson("""
                {"name": "n", "version": "1", "rules": [
                  {"id": "AB-001", "phase": "RUN_START", "when": {"type": "intentMatches", "regexes": ["x"]},
                   "effect": "WARN", "message": "w"},
                  {"id": "AB-002", "phase": "ENTRY", "when": {"type": "autonomyIs", "autonomy": "GUIDED"},
                   "effect": "REQUIRE_APPROVAL", "message": "a"}]}""");
        assertThat(pack.disabledRules()).isEmpty();
        assertThat(pack.rules()).extracting(DeclarativeRule::severity).containsExactly(Severity.LOW, Severity.MEDIUM);
        assertThat(pack.rules()).extracting(DeclarativeRule::description).containsExactly("", "");
    }

    @Test
    void rejectsMalformedJsonAndDuplicateKeys() {
        assertThat(problems("{not json")).singleElement().asString().startsWith("$: not valid JSON");
        assertThat(problems("[]")).containsExactly("$: a policy pack must be a JSON object");
        assertThat(problems("{\"name\": \"a\", \"name\": \"b\", \"version\": \"1\"}")).singleElement().asString()
                .startsWith("$: not valid JSON").contains("Duplicate");
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevel() {
        assertThat(problems("{\"name\": \"a\", \"version\": \"1\", \"owner\": \"x\"}")).containsExactly(
                "$.owner: unknown field (allowed: disabledRules, name, rules, version)");
        assertThat(problems(pack(RULE.replace("\"description\"", "\"descripton\""))))
                .containsExactly("$.rules[0].descripton: unknown field (allowed: description, effect, id, message, "
                        + "phase, severity, stages, when)");
        assertThat(problems(pack(RULE.replace("\"globs\"", "\"glob\"")))).containsExactly(
                "$.rules[0].when.glob: unknown field (allowed: globs, type)",
                "$.rules[0].when.globs: required");
    }

    @Test
    void rejectsMissingAndMistypedFields() {
        assertThat(problems("{\"rules\": {}}")).containsExactly("$.name: required", "$.version: required",
                "$.rules: must be an array");
        assertThat(problems(pack("{\"phase\": \"EXIT\"}"))).containsExactly("$.rules[0].id: required",
                "$.rules[0].effect: required", "$.rules[0].message: required", "$.rules[0].when: required");
        assertThat(problems(pack(RULE.replace("[\"implement\"]", "\"implement\"")))).containsExactly(
                "$.rules[0].stages: must be an array of strings");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"changeSizeOver\", \"files\": \"ten\", \"lines\": 1.5}",
                "DENY", "")))).containsExactly("$.rules[0].when.files: must be an integer",
                "$.rules[0].when.lines: must be an integer");
        assertThat(problems(pack("42"))).containsExactly("$.rules[0]: a rule must be a JSON object");
    }

    @Test
    void rejectsUnknownEnumValuesAndConditionTypes() {
        assertThat(problems(pack(RULE.replace("\"EXIT\"", "\"EXITING\"")))).containsExactly(
                "$.rules[0].phase: unknown value 'EXITING' (allowed: RUN_START, ENTRY, EXIT)");
        assertThat(problems(pack(RULE.replace("REQUIRE_APPROVAL", "BLOCK")))).containsExactly(
                "$.rules[0].effect: unknown value 'BLOCK' (allowed: DENY, REQUIRE_APPROVAL, WARN)");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"riskAtLeast\", \"level\": \"EXTREME\"}", "DENY", ""))))
                .containsExactly("$.rules[0].when.level: unknown value 'EXTREME' (allowed: LOW, MEDIUM, HIGH, "
                        + "CRITICAL)");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"timeOfDay\"}", "DENY", "")))).singleElement().asString()
                .startsWith("$.rules[0].when.type: unknown condition type 'timeOfDay' (allowed: allOf, anyOf, ");
        assertThat(problems(pack(rule("EXIT", "{\"globs\": [\"x\"]}", "DENY", "")))).containsExactly(
                "$.rules[0].when.type: required");
        assertThat(problems(pack(rule("EXIT", "[\"x\"]", "DENY", "")))).containsExactly(
                "$.rules[0].when: a condition must be a JSON object with a \"type\"");
    }

    @Test
    void rejectsInvalidRegexesWithTheirPosition() {
        assertThat(problems(pack(rule("RUN_START", "{\"type\": \"intentMatches\", \"regexes\": [\"ok\", \"(unclosed\"]}",
                "DENY", "")))).singleElement().asString()
                .startsWith("$.rules[0].when.regexes[1]: invalid regex '(unclosed': Unclosed group");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"payloadMatches\", \"regexes\": []}", "DENY", ""))))
                .containsExactly("$.rules[0].when.regexes: must list at least one regex");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"payloadMatches\", \"regexes\": [\" \"]}", "DENY", ""))))
                .containsExactly("$.rules[0].when.regexes[0]: must not be blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src\\\\security\\\\**", "/etc/passwd", "C:/repo/**", "url-shortener/../secrets/**", ""})
    void rejectsInvalidGlobs(String glob) {
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"pathMatches\", \"globs\": [\"" + glob + "\"]}", "DENY",
                "")))).singleElement().asString().startsWith("$.rules[0].when.globs[0]: ");
    }

    @Test
    void rejectsBadIdsDuplicatesAndBuiltInCollisions() {
        assertThat(problems(pack(RULE.replace("ORG-001", "org-1")))).containsExactly(
                "$.rules[0].id: 'org-1' must match [A-Z]{2,5}-\\d{3} (for example ORG-001)");
        assertThat(problems(pack(RULE + ", " + RULE))).containsExactly(
                "$.rules[1].id: duplicate rule id 'ORG-001' (also $.rules[0].id)");
        assertThat(problems(pack(RULE.replace("ORG-001", "SEC-001")))).containsExactly(
                "$.rules[0].id: 'SEC-001' collides with a built-in rule id; disable the built-in rule with "
                        + "disabledRules and choose another id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHG-002", "GOV-003", "SEC-002"})
    void rejectsDisablingTheReleaseGateAndTheSecretLeakGuard(String id) {
        assertThat(problems(pack(RULE, "[\"" + id + "\"]"))).singleElement().asString()
                .isEqualTo("$.disabledRules[0]: '" + id + "' cannot be disabled (the release approval gate and the "
                        + "secret-leak guard are always enforced; protected rules: CHG-002, GOV-003, SEC-002)");
        assertThat(PolicyPack.NON_DISABLEABLE_RULE_IDS).contains(id);
    }

    @Test
    void rejectsDisablingUnknownOrRepeatedRules() {
        assertThat(problems(pack(RULE, "[\"XYZ-999\", \"CHG-001\", \"CHG-001\"]"))).containsExactly(
                "$.disabledRules[0]: 'XYZ-999' is not a built-in rule (built-in rules: SEC-001, SEC-002, SEC-003, "
                        + "CMP-001, CMP-002, CHG-001, CHG-002, CHG-003, GOV-001, GOV-002, GOV-003)",
                "$.disabledRules[2]: 'CHG-001' is listed more than once");
    }

    @Test
    void rejectsRulesThatCannotApplyAsWritten() {
        assertThat(problems(pack(rule("RUN_START", "{\"type\": \"intentMatches\", \"regexes\": [\"x\"]}", "DENY",
                ", \"stages\": [\"intake\"]")))).containsExactly(
                "$.rules[0].stages: must be empty for RUN_START rules (no stage is active at run start)");
        assertThat(problems(pack(rule("ENTRY", "{\"type\": \"not\", \"condition\": {\"type\": \"payloadMatches\", "
                + "\"regexes\": [\"x\"]}}", "DENY", "")))).containsExactly("$.rules[0].when.condition: payloadMatches "
                + "only applies to EXIT rules (only exit gates have a candidate payload)");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"autonomyIs\", \"autonomy\": \"GUIDED\"}", "WARN",
                ", \"severity\": \"HIGH\"")))).containsExactly("$.rules[0].severity: a WARN rule always produces a "
                + "LOW finding that never blocks; omit the severity or use LOW");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"allOf\", \"conditions\": []}", "DENY", ""))))
                .containsExactly("$.rules[0].when.conditions: must list at least one condition");
        assertThat(problems(pack(rule("EXIT", "{\"type\": \"changeSizeOver\", \"files\": -1, \"lines\": 0}", "DENY",
                "")))).containsExactly("$.rules[0].when.files: must not be negative");
        assertThat(problems(pack(RULE.replace("touches security code", " ")))).containsExactly(
                "$.rules[0].message: must not be blank");
        assertThat(problems("{\"name\": \" \", \"version\": \"\"}")).containsExactly("$.name: must not be blank",
                "$.version: must not be blank");
    }

    @Test
    void rejectsConditionsNestedTooDeeply() {
        String condition = "{\"type\": \"autonomyIs\", \"autonomy\": \"GUIDED\"}";
        for (int i = 0; i < PolicyPackLoader.MAX_CONDITION_DEPTH; i++) {
            condition = "{\"type\": \"not\", \"condition\": " + condition + "}";
        }
        assertThat(problems(pack(rule("EXIT", condition, "DENY", "")))).singleElement().asString()
                .endsWith(": conditions are nested deeper than 16 levels");
    }

    @Test
    void reportsEveryProblemOfThePackAtOnce() {
        List<String> problems = problems(pack(RULE.replace("ORG-001", "SEC-002") + ", "
                + rule("EXIT", "{\"type\": \"intentMatches\", \"regexes\": [\"[\"]}", "BLOCK", ""), "[\"SEC-002\"]"));
        assertThat(problems).hasSize(3).anySatisfy(p -> assertThat(p).startsWith("$.rules[0].id: 'SEC-002' collides"))
                .anySatisfy(p -> assertThat(p).startsWith("$.rules[1].effect: unknown value 'BLOCK'"))
                .anySatisfy(p -> assertThat(p).startsWith("$.disabledRules[0]: 'SEC-002' cannot be disabled"));
    }

    @Test
    void validatesPacksBuiltInCode() {
        DeclarativeRule bad = new DeclarativeRule("CHG-001", "", GatePhase.EXIT, List.of(),
                new Condition.PathMatches(List.of("..\\x")), Effect.DENY, null, "m");
        assertThatThrownBy(() -> PolicyPackLoader.validate(new PolicyPack("p", "1", List.of(bad), List.of("SEC-002"))))
                .isInstanceOfSatisfying(PolicyPackException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.rules[0].id: 'CHG-001' collides with a built-in rule id; disable the built-in rule with "
                                + "disabledRules and choose another id",
                        "$.rules[0].when.globs[0]: invalid glob '..\\x': use forward slashes",
                        "$.disabledRules[0]: 'SEC-002' cannot be disabled (the release approval gate and the "
                                + "secret-leak guard are always enforced; protected rules: CHG-002, GOV-003, SEC-002)"));
    }

    @Test
    void loadsTheBundledSamplePack() {
        PolicyPack sample = PolicyPackLoader.sample();
        assertThat(sample.name()).isEqualTo("sample-baseline");
        assertThat(sample.rules()).extracting(DeclarativeRule::id).containsExactly("ORG-001", "ORG-002", "ORG-003",
                "ORG-004");
        assertThat(sample.rules()).extracting(DeclarativeRule::effect).containsExactly(Effect.DENY,
                Effect.REQUIRE_APPROVAL, Effect.WARN, Effect.REQUIRE_APPROVAL);
        assertThat(sample.hash()).isEqualTo(PolicyPackLoader.sample().hash());
        assertThatThrownBy(() -> PolicyPackLoader.fromResource("/policy/missing.json"))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
