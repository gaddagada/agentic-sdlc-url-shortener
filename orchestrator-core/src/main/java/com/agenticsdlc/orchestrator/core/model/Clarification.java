package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Clarification questions asked by a stage (normally {@code intake}) and the human answers.
 *
 * @param stageId stage that asked
 * @param questions the questions
 * @param answers answers by question id (empty until answered)
 * @param requestedAt when the questions were asked
 * @param answeredAt when they were answered
 * @param answeredBy operator who answered
 */
public record Clarification(String stageId, List<ClarificationQuestion> questions, Map<String, String> answers,
                            Instant requestedAt, @Nullable Instant answeredAt, @Nullable String answeredBy) {

    /**
     * Normalizes collections.
     */
    public Clarification {
        questions = Immutables.list(questions);
        answers = Immutables.map(answers);
    }

    /**
     * Returns whether answers were provided.
     *
     * @return true when answered
     */
    public boolean answered() {
        return answeredAt != null;
    }

    /**
     * Returns an answered copy.
     *
     * @param newAnswers answers by question id
     * @param operatorId answering operator
     * @param at answer time
     * @return the answered clarification
     */
    public Clarification answer(Map<String, String> newAnswers, String operatorId, Instant at) {
        return new Clarification(stageId, questions, newAnswers, requestedAt, at, operatorId);
    }
}
