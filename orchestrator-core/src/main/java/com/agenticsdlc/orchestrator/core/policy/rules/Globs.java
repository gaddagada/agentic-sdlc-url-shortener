package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.util.RepoPaths;

/**
 * Minimal glob-to-regex conversion for forward-slash repository paths: {@code **}{@code /} matches zero or
 * more directories, {@code **} any characters, {@code *} any characters except {@code /}, {@code ?} one
 * character except {@code /}. Patterns match case-insensitively (protected files must stay protected on
 * case-insensitive file systems, where {@code POM.XML} is {@code pom.xml}); match them against
 * {@link #normalize(String) normalized} paths. Shared by the built-in protected-path rule and the {@code pathMatches}
 * condition of declarative policy packs.
 */
public final class Globs {

    private Globs() {
    }

    /**
     * Converts a glob to a case-insensitive pattern; every character other than the wildcards matches literally.
     *
     * @param glob the glob, for example {@code **}{@code /db/migration/V1__*}
     * @return the pattern (match it with {@link java.util.regex.Matcher#matches()} against a normalized path)
     */
    public static Pattern toPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 3;
            } else if (glob.startsWith("**", i)) {
                regex.append(".*");
                i += 2;
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Normalizes a path for glob matching: {@link RepoPaths#normalize(String)} (backslashes, repeated slashes and
     * {@code ./} segments), {@code ..} segments resolved lexically and leading slashes removed, so that
     * {@code url-shortener/../pom.xml} is matched as {@code pom.xml}.
     *
     * @param path repository path
     * @return the normalized path
     */
    public static String normalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : RepoPaths.normalize(path).split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                segments.pollLast();
            } else {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }
}
