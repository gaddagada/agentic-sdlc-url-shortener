package com.agenticsdlc.orchestrator.core.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import com.agenticsdlc.orchestrator.core.util.RepoPaths;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;

/**
 * Reads the source of the files a change touches, so that agents write diffs against the real text instead of
 * guessing it from the codebase index. Reading is bounded and confined:
 *
 * <ul>
 *   <li>paths are repository-relative; absolute paths, {@code ..} segments, segments ending with a dot or a space
 *       (Windows drops them silently), paths that resolve (also through
 *       symbolic links) outside the workspace root, secret-bearing files ({@link WorkspaceSandbox#isSecretFile})
 *       and files under {@link WorkspaceSandbox#SKIPPED_DIRECTORIES} are refused;</li>
 *   <li>binary files (a NUL byte, or not valid UTF-8) are skipped;</li>
 *   <li>credential-like tokens ({@link SecretPatterns}) are replaced with {@value SecretPatterns#REDACTED} before
 *       the source leaves the workspace (it is sent to the LLM provider), with a note naming the file;</li>
 *   <li>each file is capped at {@code perFileChars} and all files together at {@code totalChars}; a cut file ends
 *       with a {@value #TRUNCATED_MARKER} marker line;</li>
 *   <li>requests are served by priority ({@link Priority#MODIFY} targets first, then {@link Priority#EXTEND}, then
 *       {@link Priority#REGRESSION_RISK}), in request order within a priority; a regression risk is included only
 *       when it fits completely into the remaining budget.</li>
 * </ul>
 *
 * <p>Every file that is not included in full or verbatim gets a note ("truncated", "not found", "refused",
 * "skipped", "omitted", "redacted"), so prompts can say what is missing.</p>
 */
public final class ImpactedSources {

    /** Marker line appended to a file whose content was cut. */
    public static final String TRUNCATED_MARKER = "[truncated]";

    /** Bytes inspected for NUL characters when detecting binary files. */
    private static final int BINARY_PROBE_BYTES = 8_192;

    /** A truncated fragment shorter than this is omitted instead (it would not help an agent). */
    static final int MIN_FRAGMENT_CHARS = 400;

    private ImpactedSources() {
    }

    /** Why a file is requested; the declaration order is the reading priority. */
    public enum Priority {
        /** A file the change modifies or deletes (also files named by verification feedback). */
        MODIFY,
        /** A file the change extends. */
        EXTEND,
        /** A file that is not changed but may regress; read only when the budget still allows it in full. */
        REGRESSION_RISK
    }

    /**
     * A file to read.
     *
     * @param path repository-relative path (forward slashes)
     * @param priority reading priority
     */
    public record Request(String path, Priority priority) {
        /**
         * Validates the request.
         */
        public Request {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(priority, "priority");
        }
    }

    /**
     * The sources read and the notes about files that were cut or left out.
     *
     * @param sources normalized path to content, sorted by path (unmodifiable)
     * @param notes one note per file that was truncated, not found, refused, skipped or omitted, in priority order
     */
    public record Result(SortedMap<String, String> sources, List<String> notes) {
        /**
         * Copies the collections.
         */
        public Result {
            sources = Collections.unmodifiableSortedMap(new TreeMap<>(sources));
            notes = List.copyOf(notes);
        }

        /**
         * Returns an empty result.
         *
         * @return empty sources and notes
         */
        public static Result empty() {
            return new Result(new TreeMap<>(), List.of());
        }

        /**
         * Returns the total number of characters of all sources.
         *
         * @return characters
         */
        public int totalChars() {
            return sources.values().stream().mapToInt(String::length).sum();
        }
    }

    /**
     * Reads the requested files.
     *
     * @param workspaceRoot repository root
     * @param requests requested files (duplicates keep their highest priority)
     * @param perFileChars maximum characters per file (at least 1)
     * @param totalChars maximum characters of all files together (at least 1)
     * @return the sources and notes
     */
    public static Result read(Path workspaceRoot, Collection<Request> requests, int perFileChars, int totalChars) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        if (perFileChars < 1 || totalChars < 1) {
            throw new IllegalArgumentException("source limits must be positive");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            return new Result(new TreeMap<>(), List.of("workspace root " + root + " is not readable; no sources"));
        }
        Map<String, Priority> wanted = new LinkedHashMap<>();
        for (Request request : requests) {
            String path = RepoPaths.normalize(request.path());
            if (path.isEmpty()) {
                continue;
            }
            wanted.merge(path, request.priority(), (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        List<Map.Entry<String, Priority>> ordered = new ArrayList<>(wanted.entrySet());
        ordered.sort(Comparator.comparingInt(e -> e.getValue().ordinal())); // stable: request order within a priority
        SortedMap<String, String> sources = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        int remaining = totalChars;
        for (Map.Entry<String, Priority> entry : ordered) {
            String path = entry.getKey();
            Priority priority = entry.getValue();
            String refusal = refusal(path);
            Path file = null;
            if (refusal == null) {
                try {
                    file = root.resolve(path).normalize();
                    if (!file.startsWith(root) || file.equals(root)) {
                        refusal = "outside the workspace root";
                    }
                } catch (InvalidPathException e) {
                    refusal = "not a valid path";
                }
            }
            if (refusal != null) {
                notes.add(path + ": refused (" + refusal + ")");
                continue;
            }
            if (!Files.isRegularFile(file)) {
                notes.add(path + ": not found in the workspace" + (Files.exists(file) ? " (not a regular file)" : ""));
                continue;
            }
            Text text;
            try {
                if (!file.toRealPath().startsWith(realRoot)) {
                    notes.add(path + ": refused (resolves outside the workspace root)");
                    continue;
                }
                text = readText(file, perFileChars);
            } catch (IOException e) {
                notes.add(path + ": not readable (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            if (text == null) {
                notes.add(path + ": skipped (binary or not UTF-8 text)");
                continue;
            }
            String redacted = SecretPatterns.redact(text.content());
            if (!redacted.equals(text.content())) {
                text = new Text(redacted, text.complete(), text.sizeBytes());
                notes.add(path + ": credential-like tokens redacted (shown as " + SecretPatterns.REDACTED
                        + "); do not use redacted lines as diff context");
            }
            int allowed = Math.min(perFileChars, remaining);
            if (text.content().length() <= allowed) {
                sources.put(path, text.content());
                remaining -= text.content().length();
                continue;
            }
            if (priority == Priority.REGRESSION_RISK && text.content().length() <= perFileChars) {
                notes.add(path + ": omitted (regression risk; " + text.content().length() + " characters do not fit "
                        + "the remaining source budget of " + remaining + ")");
                continue;
            }
            if (allowed < Math.min(MIN_FRAGMENT_CHARS, perFileChars)
                    || priority == Priority.REGRESSION_RISK && allowed < perFileChars) {
                notes.add(path + ": omitted (total source budget of " + totalChars + " characters exhausted)");
                continue;
            }
            String content = cut(text, allowed);
            sources.put(path, content);
            remaining -= content.length();
            notes.add(path + ": truncated to " + allowed + " of " + text.describeLength() + " ("
                    + (allowed < perFileChars ? "total source budget of " + totalChars : "per-file limit of "
                    + perFileChars) + " characters)");
        }
        return new Result(sources, notes);
    }

    /** Returns why a normalized path is refused before touching the file system, or null. */
    private static String refusal(String path) {
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || RepoPaths.hasParentSegment(path)) {
            return "outside the workspace root";
        }
        String spelling = WorkspaceSandbox.spellingProblem(path);
        if (spelling != null) {
            return "the path " + spelling;
        }
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (WorkspaceSandbox.SKIPPED_DIRECTORIES.contains(segment)
                    || segment.toLowerCase(Locale.ROOT).startsWith(".env")) {
                return "excluded directory " + segment;
            }
        }
        if (WorkspaceSandbox.isSecretFile(segments[segments.length - 1])) {
            return "secret-bearing file";
        }
        return null;
    }

    /** Text read from a file: the decoded content and whether the whole file was read. */
    private record Text(String content, boolean complete, long sizeBytes) {
        String describeLength() {
            return complete ? content.length() + " characters" : "a " + sizeBytes + "-byte file";
        }
    }

    /**
     * Reads at most enough bytes for {@code perFileChars} characters plus one (to detect truncation). Returns null
     * for binary content: a NUL byte in the first {@value #BINARY_PROBE_BYTES} bytes, or a completely read file that
     * is not valid UTF-8.
     */
    private static Text readText(Path file, int perFileChars) throws IOException {
        long size = Files.size(file);
        long limit = Math.min(size, Math.min((long) perFileChars * 4L + 4L, Integer.MAX_VALUE - 8L));
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.toRealPath())) {
            bytes = in.readNBytes((int) limit);
        }
        int probe = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        boolean complete = bytes.length >= size;
        CodingErrorAction action = complete ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(action)
                    .onUnmappableCharacter(action).decode(ByteBuffer.wrap(bytes)).toString();
            // a partially read file decodes to more than perFileChars characters, so it is always cut
            return new Text(text, complete, size);
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Cuts text to at most {@code max} characters, the marker line included, preferably at a line end. */
    private static String cut(Text text, int max) {
        String marker = "\n... " + TRUNCATED_MARKER + " (" + text.describeLength() + "; the rest is not shown)\n";
        if (marker.length() * 2 > max) {
            marker = "\n" + TRUNCATED_MARKER + "\n"; // very small limits: the short marker
        }
        int keep = Math.max(0, max - marker.length());
        String content = text.content();
        int end = content.lastIndexOf('\n', keep);
        String kept = end > keep / 2 ? content.substring(0, end)
                : content.substring(0, Math.min(keep, content.length()));
        return kept + marker;
    }
}
