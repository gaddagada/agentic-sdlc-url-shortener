package com.agenticsdlc.orchestrator.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret detection patterns shared by the {@code SEC-002} policy rule and the verification agent: AWS access
 * key ids, Anthropic API keys, GitHub tokens, literal password assignments, PEM private key blocks, Slack tokens
 * and JSON web tokens.
 *
 * <p>The password pattern deliberately ignores configuration placeholders ({@code ${ENV}}, {@code {{template}}},
 * {@code <redacted>}, {@code ***}, {@code change-me}) and bare identifiers such as {@code this.password = password;}:
 * a literal password must contain at least one digit or symbol. This is a heuristic; it reduces, not eliminates,
 * false positives and negatives.
 */
public final class SecretPatterns {

    /** The patterns, with a short label each. */
    public static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{10,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("(?i)(?<![a-z0-9])password\\s*[:=]\\s*[\"']?(?!\\$\\{|\\{\\{|<|\\*{3}|redacted\\b|change[-_ ]?me\\b)"
                    + "(?=[A-Za-z0-9!@#%^&*_+=\\-]*[0-9!@#%^&*+=])[A-Za-z0-9!@#%^&*_+=\\-]{6,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("xox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    private static final List<String> LABELS = List.of(
            "AWS access key id", "Anthropic API key", "GitHub token", "password assignment", "private key block",
            "Slack token", "JSON web token");

    private SecretPatterns() {
    }

    /**
     * Returns the labels of the secret kinds found in {@code text} (never the secret values themselves).
     *
     * @param text text to scan
     * @return labels of matching patterns, empty when clean
     */
    public static List<String> findKinds(String text) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < PATTERNS.size(); i++) {
            Matcher matcher = PATTERNS.get(i).matcher(text);
            if (matcher.find()) {
                found.add(LABELS.get(i));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns whether {@code text} contains anything that looks like a secret.
     *
     * @param text text to scan
     * @return true when a pattern matches
     */
    public static boolean containsSecret(String text) {
        return !findKinds(text).isEmpty();
    }
}
