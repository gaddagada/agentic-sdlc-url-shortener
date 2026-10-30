package com.agenticsdlc.orchestrator.core.agent;

import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;

/**
 * The intrinsic clarification gate of the requirements stage, shared by deterministic and LLM agents: a
 * requirement whose ambiguity score reaches the threshold, with no clarification answers yet, needs
 * clarification before planning.
 */
public final class ClarificationGate {

    private ClarificationGate() {
    }

    /**
     * Returns the questions to ask, if the gate fires.
     *
     * @param spec the requirement specification produced by the agent
     * @param context the stage context
     * @return the questions, or empty when no clarification is needed
     */
    public static Optional<List<ClarificationQuestion>> questionsIfNeeded(RequirementSpec spec,
                                                                          StageContext context) {
        if (context.hasClarificationAnswers() || spec.ambiguity() == null) {
            return Optional.empty();
        }
        if (spec.ambiguity().score() >= context.settings().ambiguityThreshold()
                && !spec.ambiguity().questions().isEmpty()) {
            return Optional.of(spec.ambiguity().questions());
        }
        return Optional.empty();
    }
}
