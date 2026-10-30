package com.agenticsdlc.orchestrator.core.agent;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * The outcome of an agent attempt.
 */
public sealed interface StageResult permits StageResult.Success, StageResult.NeedsClarification, StageResult.Failure {

    /**
     * The agent produced a payload.
     *
     * @param payload the payload (validated and gated by the engine)
     * @param rationale the agent's rationale (audited as AGENT_DECISION)
     * @param compensations compensations for side effects the agent produced
     * @param llm LLM usage, or null for deterministic agents
     */
    record Success(ArtifactPayload payload, String rationale, List<CompensationRequest> compensations,
                   @Nullable LlmTrace llm) implements StageResult {
        /**
         * Validates and normalizes fields.
         */
        public Success {
            Objects.requireNonNull(payload, "payload");
            rationale = Immutables.text(rationale);
            compensations = Immutables.list(compensations);
        }
    }

    /**
     * The agent needs answers from a human before it can produce its payload.
     *
     * @param questions the questions
     * @param rationale why clarification is needed
     * @param llm LLM usage, or null
     */
    record NeedsClarification(List<ClarificationQuestion> questions, String rationale, @Nullable LlmTrace llm)
            implements StageResult {
        /**
         * Validates and normalizes fields.
         */
        public NeedsClarification {
            questions = Immutables.list(questions);
            rationale = Immutables.text(rationale);
            if (questions.isEmpty()) {
                throw new IllegalArgumentException("clarification requires at least one question");
            }
        }
    }

    /**
     * The attempt failed.
     *
     * @param failure the classified failure
     */
    record Failure(StageFailure failure) implements StageResult {
        /**
         * Validates fields.
         */
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * Creates a success without compensations.
     *
     * @param payload payload
     * @param rationale rationale
     * @return the result
     */
    static Success success(ArtifactPayload payload, String rationale) {
        return new Success(payload, rationale, List.of(), null);
    }

    /**
     * Creates a success with compensations.
     *
     * @param payload payload
     * @param rationale rationale
     * @param compensations compensations
     * @return the result
     */
    static Success success(ArtifactPayload payload, String rationale, List<CompensationRequest> compensations) {
        return new Success(payload, rationale, compensations, null);
    }

    /**
     * Creates a clarification request.
     *
     * @param questions questions
     * @return the result
     */
    static NeedsClarification needsClarification(List<ClarificationQuestion> questions) {
        return new NeedsClarification(questions, "the requirement is too ambiguous to plan", null);
    }

    /**
     * Creates a failure.
     *
     * @param failure the failure
     * @return the result
     */
    static Failure failure(StageFailure failure) {
        return new Failure(failure);
    }
}
