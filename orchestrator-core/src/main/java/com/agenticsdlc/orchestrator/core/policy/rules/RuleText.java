package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;

/**
 * Text helpers shared by the requirement-text rules.
 */
final class RuleText {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;])\\s+|\\n+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    private RuleText() {
    }

    /**
     * Returns the requirement texts a requirement-text rule must inspect: at RUN_START the requirement under
     * evaluation; at the exit of the requirements stage also the normalized statement of the spec. Other
     * phases return an empty list.
     */
    static List<String> requirementTexts(PolicyContext ctx) {
        if (ctx.phase() == GatePhase.RUN_START) {
            return List.of(ctx.requirement());
        }
        RequirementSpec spec = ctx.candidate(RequirementSpec.class);
        if (spec != null) {
            return List.of(ctx.requirement(), spec.normalizedStatement());
        }
        return List.of();
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    static List<String> sentences(String text) {
        return Arrays.stream(SENTENCE_SPLIT.split(normalize(text))).filter(s -> !s.isBlank()).toList();
    }

    static List<String> tokens(String sentence) {
        List<String> tokens = new ArrayList<>();
        for (String token : NON_WORD.split(sentence.toLowerCase(Locale.ROOT))) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
