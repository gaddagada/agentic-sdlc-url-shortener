package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;

/**
 * A clarification question.
 *
 * @param id question id ({@code q1}, ...)
 * @param question the question
 * @param why why it is asked
 * @param suggestedAnswers suggested answers
 */
public record ClarificationQuestionView(String id, String question, String why, List<String> suggestedAnswers) {

    /**
     * Maps a core question.
     *
     * @param question the question
     * @return the view
     */
    public static ClarificationQuestionView from(ClarificationQuestion question) {
        return new ClarificationQuestionView(question.id(), question.question(), question.why(),
                question.suggestedAnswers());
    }
}
