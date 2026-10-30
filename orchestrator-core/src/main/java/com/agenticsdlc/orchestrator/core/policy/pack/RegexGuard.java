package com.agenticsdlc.orchestrator.core.policy.pack;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Guards the regexes of declarative policy rules against catastrophic backtracking (ReDoS): policy rules are
 * evaluated while the engine holds a run's lock, against texts that agents (LLMs) produce.
 *
 * <ul>
 *   <li>at load time ({@link #problem(String)}): a regex longer than {@value #MAX_REGEX_LENGTH} characters is
 *       refused, and so is a repeated group that itself contains an unbounded quantifier ({@code (a+)+},
 *       {@code (.*)*}, {@code (?:x*y)+}, {@code (a+){2,}}), the classic shape of exponential backtracking (use a
 *       possessive quantifier such as {@code a++} or an atomic group {@code (?>...)} instead);</li>
 *   <li>at evaluation time ({@link #find(Pattern, CharSequence)}): every search runs with a time budget of
 *       {@link #MATCH_TIME_BUDGET}; a search that exceeds it throws {@link RegexTimeoutException}, so that the
 *       policy engine fails closed (a DENY finding) instead of stalling the run. This also covers catastrophic
 *       patterns the load-time heuristic cannot recognise, such as overlapping alternations ({@code (a|aa)+}).</li>
 * </ul>
 */
final class RegexGuard {

    /** Maximum length of a policy rule regex. */
    static final int MAX_REGEX_LENGTH = 1000;

    /** Maximum time one regex search of a policy rule may take. */
    static final Duration MATCH_TIME_BUDGET = Duration.ofSeconds(1);

    private RegexGuard() {
    }

    /**
     * Returns why a regex is refused for a policy rule, or null when it is acceptable.
     *
     * @param regex the regex (syntactically valid)
     * @return the problem, or null
     */
    static @Nullable String problem(String regex) {
        if (regex.length() > MAX_REGEX_LENGTH) {
            return "regex is longer than " + MAX_REGEX_LENGTH + " characters";
        }
        if (hasNestedQuantifier(regex)) {
            return "nested quantifier: a repeated group contains an unbounded quantifier (like (a+)+), which can "
                    + "backtrack catastrophically; use a possessive quantifier (a++) or an atomic group (?>...)";
        }
        return null;
    }

    /**
     * Returns whether the regex repeats (with {@code *}, {@code +} or a counted quantifier allowing more than one
     * repetition) a group that contains an unbounded, non-possessive quantifier ({@code *}, {@code +},
     * {@code {n,}}). Escapes, quoted sections and character classes are skipped; atomic groups are safe.
     *
     * @param regex the regex
     * @return true for a nested quantifier
     */
    static boolean hasNestedQuantifier(String regex) {
        Deque<boolean[]> groups = new ArrayDeque<>(); // [containsUnboundedQuantifier, atomic]
        int length = regex.length();
        int i = 0;
        while (i < length) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 < length && regex.charAt(i + 1) == 'Q') {
                    int end = regex.indexOf("\\E", i + 2);
                    i = end < 0 ? length : end + 2;
                } else {
                    i += 2;
                }
                continue;
            }
            if (c == '[') {
                i = skipClass(regex, i);
                continue;
            }
            if (c == '(') {
                groups.push(new boolean[] {false, regex.startsWith("(?>", i)});
                i++;
                continue;
            }
            if (c == ')') {
                boolean[] group = groups.isEmpty() ? new boolean[] {false, false} : groups.pop();
                boolean risky = group[0] && !group[1];
                int next = i + 1;
                if (risky && repeats(regex, next)) {
                    return true;
                }
                if (!groups.isEmpty() && (risky || (unbounded(regex, next) && !possessive(regex, next)))) {
                    groups.peek()[0] = true;
                }
                i = quantifierEnd(regex, next);
                continue;
            }
            int end = quantifierEnd(regex, i);
            if (end > i) {
                if (unbounded(regex, i) && !possessive(regex, i) && !groups.isEmpty()) {
                    groups.peek()[0] = true;
                }
                i = end;
                continue;
            }
            i++;
        }
        return false;
    }

    /**
     * Returns the index after the quantifier at {@code at} including its lazy ({@code ?}) or possessive ({@code +})
     * suffix, or {@code at} when there is no quantifier there.
     */
    private static int quantifierEnd(String regex, int at) {
        if (at >= regex.length()) {
            return at;
        }
        char c = regex.charAt(at);
        int end;
        if (c == '*' || c == '+' || c == '?') {
            end = at + 1;
        } else if (bounds(regex, at) != null) {
            end = regex.indexOf('}', at) + 1;
        } else {
            return at;
        }
        if (end < regex.length() && (regex.charAt(end) == '?' || regex.charAt(end) == '+')) {
            end++;
        }
        return end;
    }

    /** Skips a character class starting at {@code start} (nested classes and escapes included). */
    private static int skipClass(String regex, int start) {
        int depth = 0;
        int i = start;
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                depth++;
                i++;
                if (i < regex.length() && regex.charAt(i) == '^') {
                    i++;
                }
                if (i < regex.length() && regex.charAt(i) == ']') {
                    i++; // a leading ']' is literal
                }
                continue;
            }
            if (c == ']') {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            i++;
        }
        return i;
    }

    /** Whether a quantifier at {@code at} allows unbounded repetition ({@code *}, {@code +}, {@code {n,}}). */
    private static boolean unbounded(String regex, int at) {
        if (at >= regex.length()) {
            return false;
        }
        char c = regex.charAt(at);
        if (c == '*' || c == '+') {
            return true;
        }
        int[] bounds = bounds(regex, at);
        return bounds != null && bounds[1] < 0;
    }

    /** Whether a non-possessive quantifier at {@code at} allows more than one repetition. */
    private static boolean repeats(String regex, int at) {
        if (at >= regex.length() || possessive(regex, at)) {
            return false;
        }
        if (unbounded(regex, at)) {
            return true;
        }
        int[] bounds = bounds(regex, at);
        return bounds != null && bounds[1] > 1;
    }

    /** Whether the quantifier at {@code at} is possessive (followed by {@code +}). */
    private static boolean possessive(String regex, int at) {
        if (at >= regex.length()) {
            return false;
        }
        int end;
        char c = regex.charAt(at);
        if (c == '*' || c == '+' || c == '?') {
            end = at + 1;
        } else if (c == '{') {
            int close = regex.indexOf('}', at);
            if (close < 0) {
                return false;
            }
            end = close + 1;
        } else {
            return false;
        }
        return end < regex.length() && regex.charAt(end) == '+';
    }

    /** Parses a counted quantifier at {@code at}: [min, max] with max -1 when unbounded, or null. */
    private static int @Nullable [] bounds(String regex, int at) {
        if (at >= regex.length() || regex.charAt(at) != '{') {
            return null;
        }
        int close = regex.indexOf('}', at);
        if (close < 0) {
            return null;
        }
        String body = regex.substring(at + 1, close);
        if (!body.matches("\\d+(,\\d*)?")) {
            return null;
        }
        int comma = body.indexOf(',');
        try {
            if (comma < 0) {
                int n = Integer.parseInt(body);
                return new int[] {n, n};
            }
            String max = body.substring(comma + 1);
            return new int[] {Integer.parseInt(body.substring(0, comma)), max.isEmpty() ? -1 : Integer.parseInt(max)};
        } catch (NumberFormatException e) {
            return new int[] {0, -1};
        }
    }

    /**
     * Searches for the pattern in the text within {@link #MATCH_TIME_BUDGET}.
     *
     * @param pattern the pattern
     * @param text the text
     * @return whether the pattern is found
     * @throws RegexTimeoutException when the search exceeds the time budget
     */
    static boolean find(Pattern pattern, CharSequence text) {
        return pattern.matcher(new DeadlineCharSequence(text, System.nanoTime() + MATCH_TIME_BUDGET.toNanos(),
                pattern)).find();
    }

    /** Thrown when a policy regex search exceeds its time budget (the policy engine then fails closed). */
    static final class RegexTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RegexTimeoutException(Pattern pattern) {
            super("policy regex search exceeded " + MATCH_TIME_BUDGET.toMillis() + " ms (catastrophic backtracking?): "
                    + abbreviate(pattern.pattern()));
        }

        private static String abbreviate(String regex) {
            return regex.length() <= 80 ? regex : regex.substring(0, 77) + "...";
        }
    }

    /** A char sequence whose reads fail once a deadline has passed (checked every 4096 reads). */
    private static final class DeadlineCharSequence implements CharSequence {

        private final CharSequence text;
        private final long deadline;
        private final Pattern pattern;
        private int reads;

        DeadlineCharSequence(CharSequence text, long deadline, Pattern pattern) {
            this.text = text;
            this.deadline = deadline;
            this.pattern = pattern;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() - deadline > 0) {
                throw new RegexTimeoutException(pattern);
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.subSequence(start, end), deadline, pattern);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
