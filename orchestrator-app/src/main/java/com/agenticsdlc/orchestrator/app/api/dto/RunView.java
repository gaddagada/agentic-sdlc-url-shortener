package com.agenticsdlc.orchestrator.app.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Full view of a run (engineering specification section 6.3).
 *
 * @param id run id
 * @param scenario scenario key
 * @param status run status
 * @param haltReason halt reason, or null
 * @param haltDetail halt detail, or null
 * @param requirement original requirement
 * @param activeRequirement requirement after clarification/re-planning
 * @param autonomy autonomy level
 * @param provider agent provider
 * @param workspace workspace mode
 * @param createdBy operator who started the run, or null
 * @param planVersion plan version
 * @param createdAt creation time
 * @param startedAt start time
 * @param updatedAt last update
 * @param endedAt end time
 * @param stages stages in workflow order
 * @param pendingActions approvals and clarifications waiting for a human
 * @param clarification clarification questions and answers, or null
 * @param budget budget limits and usage
 * @param counters cumulative counters
 * @param timing latency figures
 * @param risk current risk assessment
 * @param policyFindings policy findings, oldest first
 */
public record RunView(
        String id,
        String scenario,
        String status,
        @Nullable String haltReason,
        @Nullable String haltDetail,
        String requirement,
        String activeRequirement,
        String autonomy,
        String provider,
        String workspace,
        @Nullable String createdBy,
        int planVersion,
        Instant createdAt,
        @Nullable Instant startedAt,
        Instant updatedAt,
        @Nullable Instant endedAt,
        List<StageView> stages,
        List<PendingActionView> pendingActions,
        @Nullable ClarificationView clarification,
        BudgetView budget,
        CountersView counters,
        TimingView timing,
        RiskView risk,
        List<PolicyFindingView> policyFindings) {

    /**
     * Clarification questions and answers.
     *
     * @param stageId asking stage
     * @param questions questions
     * @param answers answers by question id (empty until answered)
     * @param requestedAt when asked
     * @param answeredAt when answered
     * @param answeredBy operator who answered
     */
    public record ClarificationView(String stageId, List<ClarificationQuestionView> questions,
                                    Map<String, String> answers, Instant requestedAt, @Nullable Instant answeredAt,
                                    @Nullable String answeredBy) {
    }

    /**
     * Budget limits and usage.
     *
     * @param maxFeedbackLoops maximum feedback loops
     * @param maxReplans maximum re-plans
     * @param maxAgentCalls maximum agent calls
     * @param maxLlmTokens maximum LLM tokens
     * @param maxActiveSeconds maximum active time in seconds
     * @param agentCallsUsed agent calls used
     * @param llmTokensUsed LLM tokens used
     */
    public record BudgetView(int maxFeedbackLoops, int maxReplans, int maxAgentCalls, long maxLlmTokens,
                             long maxActiveSeconds, int agentCallsUsed, long llmTokensUsed) {
    }

    /**
     * Cumulative counters.
     *
     * @param retries retries scheduled
     * @param fallbacks deterministic fallbacks
     * @param rollbacks rollbacks
     * @param replans re-plans
     * @param feedbackLoops feedback loops
     * @param approvalsRequested approvals requested
     * @param approvalsGranted approvals granted
     * @param approvalsRejected approvals rejected
     * @param approvalsRevoked approvals revoked
     * @param agentCalls agent calls
     * @param llmCalls LLM calls
     * @param llmInputTokens LLM input tokens
     * @param llmOutputTokens LLM output tokens
     */
    public record CountersView(int retries, int fallbacks, int rollbacks, int replans, int feedbackLoops,
                               int approvalsRequested, int approvalsGranted, int approvalsRejected,
                               int approvalsRevoked, int agentCalls, int llmCalls, long llmInputTokens,
                               long llmOutputTokens) {
    }

    /**
     * Latency figures.
     *
     * @param endToEndMs end-to-end latency (null while not ended)
     * @param activeMs active time
     * @param humanWaitMs time spent waiting for humans
     * @param firstFailureAt first failed attempt
     * @param recoveredAt recovery of the first failure
     * @param mttrMs time to recover from the first failure
     */
    public record TimingView(@Nullable Long endToEndMs, long activeMs, long humanWaitMs,
                             @Nullable Instant firstFailureAt, @Nullable Instant recoveredAt, @Nullable Long mttrMs) {
    }

    /**
     * Risk assessment.
     *
     * @param level risk level
     * @param factors contributing factors
     */
    public record RiskView(String level, List<String> factors) {
    }
}
