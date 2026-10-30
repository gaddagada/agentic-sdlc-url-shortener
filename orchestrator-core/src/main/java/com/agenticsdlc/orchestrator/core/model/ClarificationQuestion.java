package com.agenticsdlc.orchestrator.core.model;

import java.util.List;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A question the requirements agent asks a human before the run can continue.
 *
 * @param id question id ({@code q1}, {@code q2}, ...)
 * @param question the question text
 * @param why why the answer matters
 * @param suggestedAnswers example answers the operator may pick from
 */
public record ClarificationQuestion(String id, String question, String why, List<String> suggestedAnswers) {

    /**
     * Normalizes fields.
     */
    public ClarificationQuestion {
        id = Immutables.text(id);
        question = Immutables.text(question);
        why = Immutables.text(why);
        suggestedAnswers = Immutables.list(suggestedAnswers);
    }
}
