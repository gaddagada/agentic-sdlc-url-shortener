package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic text helpers shared by the rule-based agents.
 */
final class Text {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9%]+");

    private Text() {
    }

    static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String s : SENTENCE_SPLIT.split(normalizeWhitespace(text))) {
            if (!s.isBlank()) {
                sentences.add(s.strip());
            }
        }
        return sentences;
    }

    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Whether any token equals one of the words, or (for words ending with '*') starts with the stem. */
    static boolean anyToken(Collection<String> tokens, Collection<String> words) {
        for (String token : tokens) {
            for (String word : words) {
                if (word.endsWith("*") ? token.startsWith(word.substring(0, word.length() - 1)) : token.equals(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first matching token (in word order), or null. */
    static @org.jspecify.annotations.Nullable String firstMatch(Collection<String> tokens, Collection<String> words) {
        for (String word : words) {
            for (String token : tokens) {
                if (word.endsWith("*") ? token.startsWith(word.substring(0, word.length() - 1)) : token.equals(word)) {
                    return token;
                }
            }
        }
        return null;
    }

    static boolean containsPhrase(String text, Collection<String> phrases) {
        String lower = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9%]+", " ") + " ";
        for (String phrase : phrases) {
            if (lower.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }

    static boolean hasDigit(String text) {
        return text.chars().anyMatch(Character::isDigit);
    }

    static String shorten(String text, int max) {
        String single = normalizeWhitespace(text);
        if (single.length() <= max) {
            return single;
        }
        int cut = single.lastIndexOf(' ', max - 3);
        return single.substring(0, cut > max / 2 ? cut : max - 3) + "...";
    }

    static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static String decapitalize(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    static String stripTrailingPunctuation(String text) {
        return text.replaceAll("[\\s.;:,!]+$", "").strip();
    }

    /** camelCase of the first {@code maxWords} alphanumeric words. */
    static String camelCase(String text, int maxWords) {
        List<String> words = tokens(text).stream().filter(w -> w.matches("[a-z][a-z0-9]*")).limit(maxWords).toList();
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            out.append(out.isEmpty() ? word : capitalize(word));
        }
        return out.isEmpty() ? "behaviour" : out.toString();
    }

    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
