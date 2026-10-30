package com.agenticsdlc.orchestrator.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret detection patterns shared by the {@code SEC-002} policy rule, the verification agent and the engine's
 * rationale redaction: AWS access key ids, Anthropic API keys, GitHub tokens, literal password assignments, PEM
 * private key blocks, Slack tokens, JSON web tokens and generic credential assignments ({@code api_key},
 * {@code apiKey}, {@code client_secret}, {@code secret}, {@code token}, {@code passwd}, {@code pwd}).
 *
 * <p>The assignment patterns deliberately ignore configuration placeholders ({@code ${ENV}},
 * {@code {{template}}}, {@code <redacted>}, {@code ***}, {@code change-me}) and bare identifiers or expressions
 * such as {@code this.password = password;} or {@code token = request.getHeader(...)}: a literal password needs at
 * least 6 characters and a generic credential at least 8, and both must contain at least one digit or symbol.</p>
 *
 * <p>Scan raw text (source code, individual string values), not an escaped JSON document: JSON escaping turns a
 * quote into backslash-quote, which hides quoted literals from the patterns. This is a heuristic; it reduces, not
 * eliminates, false positives and negatives.</p>
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
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)(?<![a-z0-9])(?:api[_-]?key|client[_-]?secret|secret|token|passwd|pwd)\\s*[:=]\\s*"
                    + "[\"']?(?!\\$\\{|\\{\\{|<|\\*{3}|redacted\\b|change[-_ ]?me\\b)"
                    + "(?=[A-Za-z0-9!@#%^&*_+=\\-]*[0-9!@#%^&*+=])[A-Za-z0-9!@#%^&*_+=\\-]{8,}"));

    private static final List<String> LABELS = List.of(
            "AWS access key id", "Anthropic API key", "GitHub token", "password assignment", "private key block",
            "Slack token", "JSON web token", "credential assignment");

    /** Replacement of a credential-like token in {@link #redact(String)}. */
    public static final String REDACTED = "[redacted]";

    private SecretPatterns() {
    }

    /**
     * Replaces every credential-like token of {@code text} with {@value #REDACTED}.
     *
     * @param text text to redact
     * @return the text without credential-like tokens (the same text when it has none)
     */
    public static String redact(String text) {
        String redacted = text;
        for (Pattern pattern : PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(REDACTED);
        }
        return redacted;
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
