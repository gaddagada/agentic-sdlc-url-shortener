package com.agenticsdlc.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.json.JsonCodec;

class RunCountersTest {

    @Test
    void accumulatesTokensCacheCountsAndCost() {
        RunCounters counters = RunCounters.ZERO.plusLlmUsage(1000, 200, 600, 100, 2_500L)
                .plusLlmUsage(400, 50, 0, 300, 1_000L).plusAgentCall().plusRetry();

        assertThat(counters.llmCalls()).isEqualTo(2);
        assertThat(counters.llmInputTokens()).isEqualTo(1400);
        assertThat(counters.llmOutputTokens()).isEqualTo(250);
        assertThat(counters.llmCacheReadTokens()).isEqualTo(600L);
        assertThat(counters.llmCacheCreationTokens()).isEqualTo(400L);
        assertThat(counters.llmCostMicroUsd()).isEqualTo(3_500L);
        assertThat(counters.llmCostUsd()).isEqualTo(0.0035);
        assertThat(counters.llmUnpricedCalls()).isZero();
        assertThat(counters.llmTokens()).isEqualTo(1650);
        assertThat(counters.agentCalls()).isEqualTo(1);
        assertThat(counters.retries()).isEqualTo(1);
        assertThat(RunCounters.ZERO.llmCostMicroUsd()).as("no LLM call costs a known 0").isZero();
    }

    @Test
    void oneUnpricedCallMakesTheRunCostUnknownForGood() {
        RunCounters counters = RunCounters.ZERO.plusLlmUsage(100, 10, 0, 0, 500L)
                .plusLlmUsage(100, 10, 0, 0, null).plusLlmUsage(100, 10, 0, 0, 700L);
        assertThat(counters.llmCostMicroUsd()).isNull();
        assertThat(counters.llmCostUsd()).isNull();
        assertThat(counters.llmUnpricedCalls()).isEqualTo(1);
        assertThat(counters.llmCalls()).isEqualTo(3);

        RunCounters legacy = RunCounters.ZERO.plusLlmUsage(10, 1).plusLlmUsage(10, 1, 5, 0);
        assertThat(legacy.llmUnpricedCalls()).as("calls without a cost are unpriced").isEqualTo(2);
        assertThat(legacy.llmCostMicroUsd()).isNull();
        assertThat(legacy.llmCacheReadTokens()).isEqualTo(5L);
    }

    @Test
    void compatibilityConstructorAndOldSnapshotsKeepTheInvariant() {
        RunCounters noLlm = new RunCounters(1, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0L, 0L);
        assertThat(noLlm.llmCostMicroUsd()).isZero();
        assertThat(noLlm.llmUnpricedCalls()).isZero();
        RunCounters withLlm = new RunCounters(0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 2, 100L, 10L);
        assertThat(withLlm.llmCostMicroUsd()).isNull();
        assertThat(withLlm.llmUnpricedCalls()).isEqualTo(2);
        assertThat(new RunCounters(0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 2, 100L, 10L, 0L, 0L, 99L, 1).llmCostMicroUsd())
                .as("a cost next to unpriced calls is not a total").isNull();

        JsonCodec codec = JsonCodec.standard();
        // a snapshot persisted before the cache and cost fields existed
        RunCounters fromOldJson = codec.fromJson("{\"agentCalls\":3,\"approvalsApproved\":0,\"approvalsRejected\":0,"
                + "\"approvalsRequested\":0,\"approvalsRevoked\":0,\"fallbacks\":0,\"feedbackLoops\":0,\"llmCalls\":2,"
                + "\"llmInputTokens\":100,\"llmOutputTokens\":10,\"replans\":0,\"retries\":0,\"rollbacks\":0}",
                RunCounters.class);
        assertThat(fromOldJson).isEqualTo(withLlm);
        assertThat(fromOldJson.llmCacheReadTokens()).isZero();
        RunCounters priced = RunCounters.ZERO.plusLlmUsage(1, 2, 3, 4, 5L);
        assertThat(codec.fromJson(codec.toJson(priced), RunCounters.class)).isEqualTo(priced);
        assertThat(codec.toJson(priced)).doesNotContain("llmCostUsd").doesNotContain("llmTokens");
    }
}
