package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.regex.Pattern;

/**
 * Minimal glob-to-regex conversion for forward-slash repository paths: {@code **}{@code /} matches zero or
 * more directories, {@code **} any characters, {@code *} any characters except {@code /}, {@code ?} one
 * character except {@code /}.
 */
final class Globs {

    private Globs() {
    }

    static Pattern toPattern(String glob) {
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
        return Pattern.compile(regex.toString());
    }
}
