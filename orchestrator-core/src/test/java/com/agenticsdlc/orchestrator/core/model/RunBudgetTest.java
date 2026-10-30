package com.agenticsdlc.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RunBudgetTest {

    @Test
    void defaultTokenBudgetCoversAFullLlmRunOfTheTenStageWorkflow() {
        assertThat(RunBudget.DEFAULT.maxLlmTokens()).isEqualTo(600_000L).isEqualTo(RunBudget.DEFAULT_MAX_LLM_TOKENS);
        assertThat(RunBudget.defaults()).isSameAs(RunBudget.DEFAULT);
        assertThat(RunBudget.DEFAULT.maxFeedbackLoops()).isEqualTo(2);
        assertThat(RunBudget.DEFAULT.maxReplans()).isEqualTo(3);
        assertThat(RunBudget.DEFAULT.maxAgentCalls()).isEqualTo(40);
        assertThat(RunBudget.DEFAULT.maxActiveDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(RunBudget.DEFAULT.withMaxLlmTokens(1_000).extended().maxLlmTokens()).isEqualTo(601_000L);
    }
}
