package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.dto.PolicyView;
import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackLoader;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;

/**
 * {@code GET /api/v1/policy}: the built-in rules with their state and, with a policy pack, the pack's name, version,
 * hash, disabled rules and declarative rules (conditions as written).
 */
@WebMvcTest(PolicyController.class)
@Import({PolicyControllerTest.Fixtures.class, SecuritySliceConfiguration.class})
class PolicyControllerTest {

    private static final PolicyPack PACK = new PolicyPack("acme", "3.1.0", PolicyPackLoader.sample().rules(),
            java.util.List.of("CHG-001"));

    @Autowired
    private MockMvcTester mvc;

    @Test
    void thePolicyInForceIsTheBuiltInRulesAndThePack() {
        MvcTestResult result = mvc.get().uri("/api/v1/policy").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.pack.name").isEqualTo("acme");
        assertThat(result).bodyJson().extractingPath("$.pack.version").isEqualTo("3.1.0");
        assertThat(result).bodyJson().extractingPath("$.pack.hash").isEqualTo(PACK.hash());
        assertThat(result).bodyJson().extractingPath("$.pack.disabledRules").asArray().containsExactly("CHG-001");
        assertThat(result).bodyJson().extractingPath("$.pack.rules[0].id").isEqualTo("ORG-001");
        assertThat(result).bodyJson().extractingPath("$.pack.rules[0].when.type").isEqualTo("allOf");
        assertThat(result).bodyJson().extractingPath("$.pack.rules[1].effect").isEqualTo("REQUIRE_APPROVAL");
        assertThat(result).bodyJson().extractingPath("$.builtInRules.length()")
                .isEqualTo(DefaultPolicyRules.all().size());
        assertThat(result).bodyJson().extractingPath("$.activeRuleIds").asArray().doesNotContain("CHG-001")
                .contains("SEC-001", "ORG-004");
    }

    @Test
    void builtInRulesReportWhetherTheyAreActiveAndWhetherAPackMayDisableThem() {
        PolicyView view = PolicyController.view(PolicyEngine.withDefaultRulesAnd(PACK), JsonCodec.standard());

        assertThat(view.builtInRules()).filteredOn(r -> r.id().equals("CHG-001")).singleElement()
                .satisfies(r -> assertThat(r.active()).isFalse());
        assertThat(view.builtInRules()).filteredOn(r -> !r.disableable()).extracting(PolicyView.BuiltInRuleView::id)
                .containsExactlyInAnyOrderElementsOf(PolicyPack.NON_DISABLEABLE_RULE_IDS);
        PolicyView builtInOnly = PolicyController.view(PolicyEngine.withDefaultRules(), JsonCodec.standard());
        assertThat(builtInOnly.pack()).isNull();
        assertThat(builtInOnly.builtInRules()).allSatisfy(r -> assertThat(r.active()).isTrue());
    }

    /**
     * A policy engine with a pack.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        PolicyEngine policyEngine() {
            return PolicyEngine.withDefaultRulesAnd(PACK);
        }

        @Bean
        JsonCodec jsonCodec() {
            return JsonCodec.standard();
        }
    }
}
