package com.agenticsdlc.orchestrator.core.policy.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyDecision;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.testsupport.Runs;

class RegexGuardTest {

    /**
     * Exponential on the JDK's regex engine despite its loop memoization (the back reference disables it), and not a
     * nested quantifier, so only the evaluation time budget can stop it.
     */
    private static final String EXPONENTIAL = "^(a|a)+(?=b)\\1";

    @ParameterizedTest
    @ValueSource(strings = {"(a+)+$", "(.*)*", "(?:x*y)+", "(a+){2,}", "(a*b?){3}", "^(\\d+\\s?)+$", "((ab)+c)*",
            "(a{1,}){2}"})
    void nestedQuantifiersAreRefused(String regex) {
        assertThat(RegexGuard.problem(regex)).as(regex).startsWith("nested quantifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\bPII\\b", "(ab|cd)+", "(a++)+", "(?>a+)+", "[(a+)+]", "\\(a+\\)+", "\\Q(a+)+\\E",
            "(a+)?", "(a+){1}", "a{2,5}(bc)*", "\"operation\":\"(MODIFY|DELETE)\",\"path\":\"[^\"]*db/migration/V1__",
            "(?i)social security number", "[\\]](a)+"})
    void ordinaryRegexesAreAccepted(String regex) {
        assertThat(RegexGuard.problem(regex)).as(regex).isNull();
    }

    @Test
    void overlongRegexesAreRefused() {
        assertThat(RegexGuard.problem("a".repeat(RegexGuard.MAX_REGEX_LENGTH))).isNull();
        assertThat(RegexGuard.problem("a".repeat(RegexGuard.MAX_REGEX_LENGTH + 1)))
                .isEqualTo("regex is longer than 1000 characters");
    }

    @Test
    void theLoaderRejectsUnsafeRegexesWithTheirPath() {
        String pack = """
                {"name": "acme", "version": "1", "rules": [
                  {"id": "ORG-001", "phase": "RUN_START", "effect": "DENY", "message": "m",
                   "when": {"type": "intentMatches", "regexes": ["ok", "(a+)+$"]}}]}""";
        assertThatThrownBy(() -> PolicyPackLoader.fromJson(pack)).isInstanceOfSatisfying(PolicyPackException.class,
                e -> assertThat(e.problems()).singleElement().asString()
                        .startsWith("$.rules[0].when.regexes[1]: unsafe regex '(a+)+$': nested quantifier"));
        assertThat(PolicyPackLoader.sample().rules()).isNotEmpty();
    }

    @Test
    void aSearchThatExceedsItsTimeBudgetThrows() {
        Pattern exponential = Pattern.compile(EXPONENTIAL);
        assertThat(RegexGuard.problem(EXPONENTIAL)).as("not recognisable statically").isNull();
        assertThat(RegexGuard.find(Pattern.compile("needle"), "hay needle hay")).isTrue();
        assertThat(RegexGuard.find(exponential, "a".repeat(8))).isFalse();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThatThrownBy(() -> RegexGuard.find(exponential, "a".repeat(64)))
                        .isInstanceOf(RegexGuard.RegexTimeoutException.class)
                        .hasMessageContaining("exceeded 1000 ms"));
    }

    @Test
    void aCatastrophicRuleRegexFailsClosedInsteadOfStallingTheGate() {
        PolicyPack pack = new PolicyPack("acme", "1.0.0", List.of(new DeclarativeRule("ORG-001", "", GatePhase.RUN_START,
                List.of(), new Condition.IntentMatches(List.of(EXPONENTIAL)), Effect.WARN, null, "never blocks")),
                List.of());
        PolicyEngine engine = PolicyEngine.withDefaultRulesAnd(pack);
        PolicyContext ctx = PolicyContext.runStart(Runs.create("Create short links.",
                RunOptions.defaults().withAutonomy(AutonomyLevel.GUIDED)), "a".repeat(64));

        PolicyDecision decision = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> engine.evaluate(ctx));

        assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
        assertThat(decision.findings()).filteredOn(f -> f.ruleId().equals("ORG-001")).singleElement()
                .satisfies(f -> {
                    assertThat(f.verdict()).isEqualTo(Verdict.DENY);
                    assertThat(f.message()).contains("RegexTimeoutException").contains("failing closed");
                });
    }
}
