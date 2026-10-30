package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code CMP-002 PiiProhibited} (RUN_START and EXIT of the requirements stage): denies requirements asking
 * to store raw or full client IP addresses, e-mail addresses in clear text, or personal data "without
 * hashing".
 */
public final class PiiProhibitedRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "CMP-002";

    private static final String IP = "(client\\s+|user\\s+|visitor\\s+|end[- ]user\\s+)?(ip|ips|ipv4|ipv6)"
            + "(\\s+address(es)?)?\\b";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\b(store|stores|storing|stored|persist|persists|persisting|save|saves|saving|record"
                    + "|records|recording|keep|keeps|keeping|log|logs|logging|retain|retains|retaining|capture"
                    + "|captures|capturing)\\b(\\s+[a-z]+){0,3}?\\s+(raw|full|complete|unhashed|unmasked|plain"
                    + "|plaintext)\\s+" + IP),
            Pattern.compile("\\b(raw|full|complete|unhashed|unmasked)\\s+" + IP
                    + "\\s+(is\\s+|are\\s+|must\\s+be\\s+|should\\s+be\\s+|will\\s+be\\s+)?(stored|persisted|saved"
                    + "|kept|logged|recorded|retained)"),
            Pattern.compile("\\be-?mails?(\\s+address(es)?)?\\s+in\\s+(the\\s+)?clear\\b"),
            Pattern.compile("\\b(clear|plain)[- ]?text\\s+e-?mails?\\b"),
            Pattern.compile("\\bwithout\\s+(any\\s+)?(hashing|hash|masking|anonymi[sz]ation|pseudonymi[sz]ation)\\b"),
            Pattern.compile("\\b(store|stores|storing|persist|persists|persisting|save|saves|saving|keep|keeps"
                    + "|keeping|log|logs|logging|record|records|recording|retain|retains|retaining)\\s+(the\\s+)?"
                    + IP + "\\s+(as[- ]is|unencrypted|unhashed|unmasked|in\\s+(plain|clear)[- ]?(form|text)"
                    + "|in\\s+the\\s+clear)\\b"));
    /** Sentences that stop collecting personal data are privacy improvements, not violations. */
    private static final Pattern PRIVACY_IMPROVEMENT = Pattern.compile(
            "\\b(remove|removes|removing|stop|stops|stopping|disable|disables|disabling|delete|deletes|deleting"
                    + "|eliminate|eliminates|eliminating|drop|drops|dropping)\\s+(the\\s+|all\\s+)?(logging|storage"
                    + "|storing|recording|collection|collecting|retention|capture|capturing|persistence)\\b");

    /**
     * Creates the rule.
     */
    public PiiProhibitedRule() {
        // stateless
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "PiiProhibited";
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
                "requirement asks to process personal data without protection: " + String.join(", ", matches)));
    }

    /**
     * Returns the prohibited personal-data phrases found in a text.
     *
     * @param text requirement text
     * @return matched phrases
     */
    static Set<String> findProhibited(String text) {
        Set<String> matches = new LinkedHashSet<>();
        for (String sentence : RuleText.sentences(text)) {
            if (PRIVACY_IMPROVEMENT.matcher(sentence).find()) {
                continue;
            }
            for (Pattern pattern : PATTERNS) {
                Matcher m = pattern.matcher(sentence);
                if (m.find()) {
                    matches.add("'" + m.group() + "'");
                }
            }
        }
        return matches;
    }
}
