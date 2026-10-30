package com.agenticsdlc.orchestrator.core.workspace;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A parsed unified diff of one file and its application to the file's text, without external libraries.
 *
 * <p>Parsing ({@link #parse(String)}) reads optional {@code ---}/{@code +++} file headers and {@code @@} hunks.
 * It is tolerant of what language models typically get wrong: preamble lines ({@code diff --git}, {@code index},
 * prose) are ignored, hunk counts are hints only (the hunk body decides; blank lines that only separate hunks are
 * dropped), a hunk header without line numbers ({@code @@ @@}) is located by its content, a context line without
 * its leading space is read as context, and trailing code fences are ignored.</p>
 *
 * <p>Application ({@link #apply(String, String)}) locates every hunk in order, never before the end of the
 * previous one: first an exact match at the declared position (corrected by the offset of the hunks before it),
 * then a whitespace-insensitive match there (leading/trailing whitespace ignored, inner runs collapsed), then the
 * nearest exact and then whitespace-insensitive match within {@value #SEARCH_WINDOW} lines, and finally a unique
 * exact match anywhere after the previous hunk. Context lines keep the file's own text (whitespace included);
 * the file's line separator (LF or CRLF) and trailing newline are preserved. A hunk that cannot be located fails
 * with a {@link PatchException} naming the hunk, the first expected line that does not match and the nearest
 * mismatch in the file, so the message can be fed back to the agent that wrote the diff.</p>
 *
 * @param oldPath path of the {@code ---} header without the {@code a/} prefix, {@code /dev/null} for a new file,
 *                or null when the diff has no file header
 * @param newPath path of the {@code +++} header without the {@code b/} prefix, or null
 * @param hunks the hunks, in file order (never empty)
 */
public record UnifiedDiff(@Nullable String oldPath, @Nullable String newPath, List<Hunk> hunks) {

    /** Lines searched above and below the declared position of a hunk. */
    public static final int SEARCH_WINDOW = 50;

    /** Path of the old side of a diff that creates a file. */
    public static final String DEV_NULL = "/dev/null";

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@+\\s*(?:-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?)?\\s*@@+(.*)$");
    private static final int MAX_QUOTED = 120;

    /**
     * Copies the hunks.
     */
    public UnifiedDiff {
        hunks = List.copyOf(hunks);
        if (hunks.isEmpty()) {
            throw new IllegalArgumentException("a diff needs at least one hunk");
        }
    }

    /** Kind of a hunk line. */
    public enum LineKind {
        /** Unchanged line ({@code ' '}). */
        CONTEXT,
        /** Removed line ({@code '-'}). */
        REMOVED,
        /** Added line ({@code '+'}). */
        ADDED
    }

    /**
     * One line of a hunk.
     *
     * @param kind kind
     * @param text text without the prefix character
     */
    public record Line(LineKind kind, String text) {
        /**
         * Validates the line.
         */
        public Line {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * One hunk.
     *
     * @param oldStart declared 1-based start line in the old file ({@code a} of {@code @@ -a,b +c,d @@}); for a
     *                 pure insertion ({@code b = 0}) the line after which the lines are inserted; 0 when the header
     *                 has no line numbers
     * @param oldCount declared old line count ({@code b})
     * @param newStart declared start line in the new file ({@code c})
     * @param newCount declared new line count ({@code d})
     * @param positionKnown whether the header carried line numbers
     * @param section text after the closing {@code @@} (for example the enclosing method), stripped
     * @param lines the lines of the hunk body
     * @param oldNoNewlineAtEnd whether a {@code \ No newline at end of file} marker follows the last old-side line
     * @param newNoNewlineAtEnd whether a {@code \ No newline at end of file} marker follows the last new-side line
     */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, boolean positionKnown, String section,
                       List<Line> lines, boolean oldNoNewlineAtEnd, boolean newNoNewlineAtEnd) {

        /**
         * Copies the lines.
         */
        public Hunk {
            lines = List.copyOf(lines);
            section = section == null ? "" : section.strip();
        }

        /**
         * Returns the lines the hunk expects in the old file (context and removed lines, in order).
         *
         * @return old-side lines
         */
        public List<String> oldLines() {
            return lines.stream().filter(l -> l.kind() != LineKind.ADDED).map(Line::text).toList();
        }

        /**
         * Returns the lines of the hunk in the new file (context and added lines, in order).
         *
         * @return new-side lines
         */
        public List<String> newLines() {
            return lines.stream().filter(l -> l.kind() != LineKind.REMOVED).map(Line::text).toList();
        }

        /**
         * Returns the hunk header as written ({@code @@ -a,b +c,d @@ section}) with the declared numbers, or
         * {@code @@ @@} when the header had none.
         *
         * @return the header
         */
        public String header() {
            String numbers = positionKnown ? "-" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " "
                    : "";
            return "@@ " + numbers + "@@" + (section.isEmpty() ? "" : " " + section);
        }
    }

    /**
     * A diff that cannot be parsed or applied. The message is self-contained and actionable (it names the file,
     * the hunk and the mismatching line).
     */
    public static final class PatchException extends IllegalArgumentException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final int hunk;

        /**
         * Creates the exception.
         *
         * @param message the message
         * @param hunk 1-based index of the failing hunk, 0 when the problem is not tied to one hunk
         */
        public PatchException(String message, int hunk) {
            super(message);
            this.hunk = hunk;
        }

        /**
         * Returns the 1-based index of the failing hunk.
         *
         * @return the hunk index, 0 when the problem is not tied to one hunk
         */
        public int hunk() {
            return hunk;
        }
    }

    // ------------------------------------------------------------------------------------------------ parsing

    /**
     * Parses a unified diff of one file.
     *
     * @param text the diff
     * @return the parsed diff
     * @throws PatchException when the text contains no hunk, a hunk without lines, or headers of more than one file
     */
    public static UnifiedDiff parse(String text) {
        Objects.requireNonNull(text, "text");
        List<String> lines = splitLines(text);
        String oldPath = null;
        String newPath = null;
        List<Hunk> hunks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (isFileHeader(lines, i)) {
                String nextOld = headerPath(line.substring(4), "a/");
                String nextNew = headerPath(lines.get(i + 1).substring(4), "b/");
                if (!hunks.isEmpty() && !(Objects.equals(nextOld, oldPath) && Objects.equals(nextNew, newPath))) {
                    throw new PatchException("the diff changes more than one file (" + displayPath(newPath, oldPath)
                            + " and " + displayPath(nextNew, nextOld) + "); give every file its own change", 0);
                }
                oldPath = nextOld;
                newPath = nextNew;
                i += 2;
                continue;
            }
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                i = parseHunk(lines, i + 1, header, hunks);
                continue;
            }
            i++; // preamble (diff --git, index, mode lines, prose) or stray text between hunks
        }
        if (hunks.isEmpty()) {
            throw new PatchException("the diff contains no hunk (expected '@@ -a,b +c,d @@' followed by ' ', '-' "
                    + "and '+' lines)", 0);
        }
        return new UnifiedDiff(oldPath, newPath, hunks);
    }

    private static int parseHunk(List<String> lines, int start, Matcher header, List<Hunk> hunks) {
        boolean known = header.group(1) != null;
        int oldStart = known ? number(header.group(1), header, hunks) : 0;
        int oldCount = known ? count(header.group(2), header, hunks) : 0;
        int newStart = known ? number(header.group(3), header, hunks) : 0;
        int newCount = known ? count(header.group(4), header, hunks) : 0;
        List<Line> body = new ArrayList<>();
        int seenOld = 0;
        int seenNew = 0;
        boolean oldNoNewline = false;
        boolean newNoNewline = false;
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i);
            boolean satisfied = known && seenOld >= oldCount && seenNew >= newCount;
            if (HUNK_HEADER.matcher(line).matches() || line.startsWith("diff --git ")
                    || (isFileHeader(lines, i) && (satisfied || !known || (i + 2 < lines.size()
                    && HUNK_HEADER.matcher(lines.get(i + 2)).matches())))) {
                break;
            }
            if (line.startsWith("```") || line.startsWith("~~~")) {
                if (satisfied || !known) {
                    break;
                }
            }
            if (line.startsWith("\\")) {
                // "\ No newline at end of file" refers to the line before it
                LineKind last = body.isEmpty() ? null : body.getLast().kind();
                if (last == LineKind.REMOVED) {
                    oldNoNewline = true;
                } else if (last == LineKind.ADDED) {
                    newNoNewline = true;
                } else if (last == LineKind.CONTEXT) {
                    oldNoNewline = true;
                    newNoNewline = true;
                }
                i++;
                continue;
            }
            char first = line.isEmpty() ? ' ' : line.charAt(0);
            Line parsed;
            if (first == ' ') {
                parsed = new Line(LineKind.CONTEXT, line.isEmpty() ? "" : line.substring(1));
            } else if (first == '-') {
                parsed = new Line(LineKind.REMOVED, line.substring(1));
            } else if (first == '+') {
                parsed = new Line(LineKind.ADDED, line.substring(1));
            } else if (!satisfied) {
                // a context line whose leading space was lost
                parsed = new Line(LineKind.CONTEXT, line);
            } else {
                break; // trailing text after a complete hunk
            }
            body.add(parsed);
            if (parsed.kind() != LineKind.ADDED) {
                seenOld++;
            }
            if (parsed.kind() != LineKind.REMOVED) {
                seenNew++;
            }
            i++;
        }
        // blank lines that only separate hunks (beyond the declared counts) are not part of the hunk
        while (known && !body.isEmpty() && body.getLast().kind() == LineKind.CONTEXT
                && body.getLast().text().isBlank() && seenOld > oldCount && seenNew > newCount) {
            body.removeLast();
            seenOld--;
            seenNew--;
        }
        if (body.isEmpty()) {
            throw new PatchException("hunk " + (hunks.size() + 1) + " (" + header.group(0).strip() + ") has no lines",
                    hunks.size() + 1);
        }
        hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, known, header.group(5), body, oldNoNewline,
                newNoNewline));
        return i;
    }

    private static int count(@Nullable String group, Matcher header, List<Hunk> hunks) {
        return group == null ? 1 : number(group, header, hunks);
    }

    private static int number(String digits, Matcher header, List<Hunk> hunks) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new PatchException("hunk " + (hunks.size() + 1) + " (" + header.group(0).strip() + ") has a line "
                    + "number out of range", hunks.size() + 1);
        }
    }

    private static boolean isFileHeader(List<String> lines, int i) {
        return lines.get(i).startsWith("--- ") && i + 1 < lines.size() && lines.get(i + 1).startsWith("+++ ");
    }

    private static @Nullable String headerPath(String raw, String prefix) {
        String path = raw;
        int tab = path.indexOf('\t');
        if (tab >= 0) {
            path = path.substring(0, tab);
        }
        path = path.strip();
        if (path.isEmpty()) {
            return null;
        }
        if (path.equals(DEV_NULL)) {
            return DEV_NULL;
        }
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static String displayPath(@Nullable String preferred, @Nullable String fallback) {
        if (preferred != null && !preferred.equals(DEV_NULL)) {
            return preferred;
        }
        return fallback == null ? "unnamed" : fallback;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast(); // the terminator of the last line, not a line
        }
        return lines;
    }

    /**
     * Returns whether this diff creates a file ({@code --- /dev/null}).
     *
     * @return true for a new-file diff
     */
    public boolean createsFile() {
        return DEV_NULL.equals(oldPath);
    }

    /**
     * Formats the diff as unified diff text. File headers are written when the parsed diff had them; hunk
     * headers carry the counts of the actual hunk lines, so the output is always self-consistent.
     *
     * @return the diff text (ending with a newline)
     */
    public String format() {
        StringBuilder out = new StringBuilder();
        if (oldPath != null || newPath != null) {
            out.append("--- ").append(oldPath == null ? DEV_NULL : oldPath.equals(DEV_NULL) ? DEV_NULL : "a/" + oldPath)
                    .append('\n');
            out.append("+++ ").append(newPath == null ? DEV_NULL : newPath.equals(DEV_NULL) ? DEV_NULL : "b/" + newPath)
                    .append('\n');
        }
        for (Hunk hunk : hunks) {
            int oldLines = hunk.oldLines().size();
            int newLines = hunk.newLines().size();
            out.append("@@ ");
            if (hunk.positionKnown()) {
                out.append('-').append(hunk.oldStart()).append(',').append(oldLines).append(" +")
                        .append(hunk.newStart()).append(',').append(newLines).append(' ');
            }
            out.append("@@");
            if (!hunk.section().isEmpty()) {
                out.append(' ').append(hunk.section());
            }
            out.append('\n');
            int lastOld = lastIndex(hunk, LineKind.ADDED);
            int lastNew = lastIndex(hunk, LineKind.REMOVED);
            for (int k = 0; k < hunk.lines().size(); k++) {
                Line line = hunk.lines().get(k);
                out.append(switch (line.kind()) {
                    case CONTEXT -> ' ';
                    case REMOVED -> '-';
                    case ADDED -> '+';
                }).append(line.text()).append('\n');
                boolean oldMarker = hunk.oldNoNewlineAtEnd() && k == lastOld;
                boolean newMarker = hunk.newNoNewlineAtEnd() && k == lastNew;
                if (oldMarker || newMarker) {
                    out.append("\\ No newline at end of file\n");
                }
            }
        }
        return out.toString();
    }

    /** Index of the last line that is not of the excluded kind (the last old- or new-side line). */
    private static int lastIndex(Hunk hunk, LineKind excluded) {
        for (int k = hunk.lines().size() - 1; k >= 0; k--) {
            if (hunk.lines().get(k).kind() != excluded) {
                return k;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ apply

    /**
     * Applies the diff to a text, naming the file as {@code the file} in error messages.
     *
     * @param original the current text (empty for a new file)
     * @return the patched text
     * @throws PatchException when a hunk cannot be located
     */
    public String apply(String original) {
        return apply(original, newPath != null && !newPath.equals(DEV_NULL) ? newPath : "the file");
    }

    /**
     * Applies the diff to a text.
     *
     * @param original the current text (empty for a new file)
     * @param fileName file name used in error messages (for example the repository-relative path)
     * @return the patched text
     * @throws PatchException when a hunk cannot be located; the message names the hunk, the first expected line
     *                        that does not match and the nearest mismatch in the file
     */
    public String apply(String original, String fileName) {
        Objects.requireNonNull(original, "original");
        String separator = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> source = splitLines(original);
        boolean endsWithNewline = original.isEmpty() || original.endsWith("\n");
        List<String> out = new ArrayList<>(source.size() + 16);
        int cursor = 0;
        int offset = 0;
        for (int index = 0; index < hunks.size(); index++) {
            Hunk hunk = hunks.get(index);
            List<String> expected = hunk.oldLines();
            int declared = declaredIndex(hunk);
            int target = hunk.positionKnown() ? clamp(declared + offset, cursor, source.size()) : cursor;
            int position = locate(source, expected, target, cursor, hunk.positionKnown());
            if (position < 0) {
                throw new PatchException(diagnose(source, hunk, index, hunks.size(), target, cursor, fileName),
                        index + 1);
            }
            out.addAll(source.subList(cursor, position));
            int p = position;
            for (Line line : hunk.lines()) {
                switch (line.kind()) {
                    case CONTEXT -> out.add(source.get(p++));
                    case REMOVED -> p++;
                    case ADDED -> out.add(line.text());
                }
            }
            cursor = p;
            if (hunk.positionKnown()) {
                offset = position - declared;
            }
            if (index == hunks.size() - 1 && cursor == source.size()) {
                if (hunk.newNoNewlineAtEnd()) {
                    endsWithNewline = false;
                } else if (hunk.oldNoNewlineAtEnd()) {
                    endsWithNewline = true;
                }
            }
        }
        out.addAll(source.subList(cursor, source.size()));
        if (out.isEmpty()) {
            return "";
        }
        return String.join(separator, out) + (endsWithNewline ? separator : "");
    }

    /** The 0-based index at which the hunk's old lines start according to its header. */
    private static int declaredIndex(Hunk hunk) {
        if (!hunk.positionKnown()) {
            return 0;
        }
        boolean pureInsertion = hunk.oldLines().isEmpty();
        // "@@ -a,0 ..." inserts after line a; otherwise line a is the first old line
        return Math.max(0, pureInsertion && hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Locates the old lines: exact at the target, whitespace-insensitive at the target, nearest exact then nearest
     * whitespace-insensitive within the search window, then a unique exact match anywhere after the cursor (for a
     * hunk without line numbers: the first match after the cursor). Returns -1 when not found.
     */
    private static int locate(List<String> source, List<String> expected, int target, int cursor,
                              boolean positionKnown) {
        if (expected.isEmpty()) {
            return target; // a pure insertion carries nothing to verify
        }
        int last = source.size() - expected.size();
        if (last < cursor) {
            return -1;
        }
        if (!positionKnown) {
            for (boolean loose : new boolean[] {false, true}) {
                for (int pos = cursor; pos <= last; pos++) {
                    if (matchesAt(source, expected, pos, loose)) {
                        return pos;
                    }
                }
            }
            return -1;
        }
        for (boolean loose : new boolean[] {false, true}) {
            if (target <= last && matchesAt(source, expected, target, loose)) {
                return target;
            }
        }
        for (boolean loose : new boolean[] {false, true}) {
            for (int distance = 1; distance <= SEARCH_WINDOW; distance++) {
                for (int pos : new int[] {target - distance, target + distance}) {
                    if (pos >= cursor && pos <= last && matchesAt(source, expected, pos, loose)) {
                        return pos;
                    }
                }
            }
        }
        int found = -1;
        for (int pos = cursor; pos <= last; pos++) {
            if (matchesAt(source, expected, pos, false)) {
                if (found >= 0) {
                    return -1; // ambiguous outside the window
                }
                found = pos;
            }
        }
        return found;
    }

    private static boolean matchesAt(List<String> source, List<String> expected, int pos, boolean loose) {
        for (int k = 0; k < expected.size(); k++) {
            String actual = source.get(pos + k);
            String wanted = expected.get(k);
            if (loose ? !normalize(actual).equals(normalize(wanted)) : !actual.equals(wanted)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String line) {
        return line.strip().replaceAll("\\s+", " ");
    }

    private static String diagnose(List<String> source, Hunk hunk, int index, int total, int target, int cursor,
                                   String fileName) {
        List<String> expected = hunk.oldLines();
        String prefix = "hunk " + (index + 1) + "/" + total + " of " + fileName + " (" + hunk.header() + "): ";
        String advice = "; rewrite the hunk against the shown source";
        int last = source.size() - expected.size();
        if (last < cursor) {
            return prefix + "the hunk expects " + expected.size() + " existing lines but only "
                    + Math.max(0, source.size() - cursor) + " remain" + (index > 0 ? " after the previous hunk" : "")
                    + " (the file has " + source.size() + " lines)" + advice;
        }
        // the candidate with most matching lines nearest to the target (whole file when the position is unknown)
        int from = hunk.positionKnown() ? Math.max(cursor, target - SEARCH_WINDOW) : cursor;
        int to = hunk.positionKnown() ? Math.min(last, target + SEARCH_WINDOW) : last;
        if (from > to) {
            from = cursor;
            to = last;
        }
        int best = from;
        int bestScore = -1;
        for (int pos = from; pos <= to; pos++) {
            int score = 0;
            for (int k = 0; k < expected.size(); k++) {
                if (normalize(source.get(pos + k)).equals(normalize(expected.get(k)))) {
                    score++;
                }
            }
            if (score > bestScore || (score == bestScore && Math.abs(pos - target) < Math.abs(best - target))) {
                best = pos;
                bestScore = score;
            }
        }
        int mismatch = 0;
        while (mismatch < expected.size()
                && normalize(source.get(best + mismatch)).equals(normalize(expected.get(mismatch)))) {
            mismatch++;
        }
        if (mismatch == expected.size()) {
            return prefix + "the hunk's lines match at line " + (best + 1) + " but also elsewhere, or only before "
                    + "the previous hunk; make the context unique and keep the hunks in file order";
        }
        String wanted = expected.get(mismatch);
        String kind = oldSideKind(hunk, mismatch) == LineKind.REMOVED ? "removed line" : "context line";
        List<String> occurrences = new ArrayList<>();
        for (int k = 0; k < source.size() && occurrences.size() < 3; k++) {
            if (normalize(source.get(k)).equals(normalize(wanted))) {
                occurrences.add(String.valueOf(k + 1));
            }
        }
        StringBuilder message = new StringBuilder(prefix).append(kind).append(" '").append(quote(wanted))
                .append("' (hunk line ").append(mismatch + 1).append(')');
        if (occurrences.isEmpty()) {
            message.append(" not found in the file");
        } else {
            message.append(mismatch == 0 ? " does not start a matching block" : " does not follow the hunk's "
                    + "previous lines").append("; the file has it at line").append(occurrences.size() > 1 ? "s " : " ")
                    .append(String.join(", ", occurrences));
        }
        int line = best + mismatch;
        message.append("; nearest match at line ").append(line + 1).append(" differs: '")
                .append(quote(source.get(line))).append("' (").append(bestScore).append(" of ")
                .append(expected.size()).append(" context/removed lines match the block at line ").append(best + 1)
                .append(')').append(advice);
        return message.toString();
    }

    private static LineKind oldSideKind(Hunk hunk, int oldIndex) {
        int seen = 0;
        for (Line line : hunk.lines()) {
            if (line.kind() == LineKind.ADDED) {
                continue;
            }
            if (seen++ == oldIndex) {
                return line.kind();
            }
        }
        return LineKind.CONTEXT;
    }

    private static String quote(String text) {
        String stripped = text.strip();
        return stripped.length() <= MAX_QUOTED ? stripped : stripped.substring(0, MAX_QUOTED - 3) + "...";
    }

    /**
     * Returns a short description of the diff for logs: file and hunk count.
     *
     * @return the description
     */
    public String describe() {
        return displayPath(newPath, oldPath) + " (" + hunks.size() + " hunk" + (hunks.size() == 1 ? "" : "s") + ")";
    }
}
