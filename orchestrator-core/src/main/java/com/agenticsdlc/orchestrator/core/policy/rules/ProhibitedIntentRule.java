package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code SEC-001 ProhibitedIntent} (RUN_START and EXIT of the requirements stage): denies requirements that
 * ask to disable, remove or bypass a control (validation, authentication, security, approvals, tests,
 * audit, logging), or contain prohibited phrases such as "skip tests", "ignore compliance", "allow any
 * destination", "log full ip", "store plaintext password" or "exfiltrate".
 */
public final class ProhibitedIntentRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "SEC-001";

    private static final Set<String> VERBS = Set.of("disable", "disables", "disabled", "disabling", "remove",
            "removes", "removed", "removing", "bypass", "bypasses", "bypassed", "bypassing");
    private static final Set<String> CONTROLS = Set.of("validation", "validations", "authentication", "auth",
            "security", "approval", "approvals", "tests", "testing", "audit", "auditing", "logging");
    private static final int WINDOW = 4;
    private static final List<Pattern> PHRASES = List.of(
            Pattern.compile("\\bskip(s|ping)?\\s+(the\\s+|all\\s+)?(tests?|testing)\\b"),
            Pattern.compile("\\bignor(e|es|ing)\\s+(the\\s+|all\\s+)?compliance\\b"),
            Pattern.compile("\\ballow(s|ing)?\\s+any\\s+destinations?\\b"),
            Pattern.compile("\\blog(s|ging)?\\s+(the\\s+)?full\\s+(client\\s+)?ip"),
            Pattern.compile("\\bstor(e|es|ing)\\s+(the\\s+|a\\s+)?(plain\\s?text|clear\\s?text)\\s+passwords?\\b"),
            Pattern.compile("\\bexfiltrat\\w*"));

    /**
     * Creates the rule.
     */
    public ProhibitedIntentRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "ProhibitedIntent";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        Set<String> matches = new LinkedHashSet<>();
        for (String text : RuleText.requirementTexts(ctx)) {
            matches.addAll(findProhibited(text));
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.CRITICAL, Verdict.DENY,
                "requirement asks to weaken a control: " + String.join(", ", matches)));
    }

    /**
     * Returns the prohibited intents found in a text (for example {@code "remove ... validation"}).
     *
     * @param text requirement text
     * @return matched intents
     */
    static Set<String> findProhibited(String text) {
        Set<String> matches = new LinkedHashSet<>();
        for (String sentence : RuleText.sentences(text)) {
            List<String> tokens = RuleText.tokens(sentence);
            for (int i = 0; i < tokens.size(); i++) {
                if (VERBS.contains(tokens.get(i))) {
                    for (int j = i + 1; j < Math.min(tokens.size(), i + 1 + WINDOW); j++) {
                        if (CONTROLS.contains(tokens.get(j))) {
                            matches.add("'" + tokens.get(i) + " ... " + tokens.get(j) + "'");
                            break;
                        }
                    }
                }
            }
            for (Pattern phrase : PHRASES) {
                var m = phrase.matcher(sentence);
                if (m.find()) {
                    matches.add("'" + m.group() + "'");
                }
            }
        }
        return matches;
    }
}
