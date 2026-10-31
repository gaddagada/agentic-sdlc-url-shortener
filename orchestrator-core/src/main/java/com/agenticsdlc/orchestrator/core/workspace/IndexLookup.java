package com.agenticsdlc.orchestrator.core.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.RepoPaths;

/**
 * Resolves what agents and reviewers write (component names in prose, simple type names, path fragments, wrong
 * package paths, identifiers in feedback) to files of a {@link CodebaseIndex}. Resolution is conservative:
 *
 * <ul>
 *   <li>a <b>type name</b> resolves only when exactly one production type has it (or, when no production type has
 *       it, exactly one test type), so {@code Link} resolves to {@code .../domain/Link.java} in a tree with one
 *       {@code Link} class and to nothing in a tree with two;</li>
 *   <li>a <b>path</b> resolves when the index lists it, else when exactly one indexed file ends with it, else by its
 *       file name (a {@code *.java} name through the type name rule, any other file name when exactly one indexed
 *       file has it), so {@code .../shortener/link/Link.java} resolves to the indexed {@code .../domain/Link.java};</li>
 *   <li>a <b>prose name</b> ({@code "Link entity"}, {@code "Link service (single and bulk create)"},
 *       {@code "CreateLink request DTO"}) resolves through the longest run of up to {@value #MAX_NAME_WORDS}
 *       consecutive words whose capitalized concatenation is such a type name, first run first
 *       ({@code LinkService}, {@code CreateLinkRequest}, {@code Link}).</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class IndexLookup {

    /** Maximum number of words concatenated into one candidate type name of a component name. */
    public static final int MAX_NAME_WORDS = 4;

    /** Maximum number of words concatenated into one candidate type name in free text (feedback, errors). */
    public static final int MAX_TEXT_WORDS = 3;

    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern JAVA_FILE = Pattern.compile("(?<![\\w$./-])([A-Za-z_$][A-Za-z0-9_$]*)\\.java(?![\\w$])");
    private static final Pattern PATH = Pattern.compile(
            "(?<![\\w./-])((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.[A-Za-z0-9]{1,10})(?![\\w/])");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t]+");

    private IndexLookup() {
    }

    /**
     * Returns the only type with a simple name: the only production type, or, when no production type has the name,
     * the only test type.
     *
     * @param index the index
     * @param simpleName simple name
     * @return the type, empty when none or more than one has the name
     */
    public static Optional<IndexedType> type(CodebaseIndex index, String simpleName) {
        List<IndexedType> named = index.typesNamed(simpleName);
        List<IndexedType> production = named.stream().filter(t -> !t.test()).toList();
        if (production.size() == 1) {
            return Optional.of(production.getFirst());
        }
        if (production.isEmpty() && named.size() == 1) {
            return Optional.of(named.getFirst());
        }
        return Optional.empty();
    }

    /**
     * Resolves a path written by an agent to an indexed file: as given when the index lists it (after normalization),
     * else the only indexed file that ends with it, else by its file name (see the class comment). Absolute paths and
     * paths with {@code ..} segments never resolve.
     *
     * @param index the index
     * @param path the path, may be null or blank
     * @return the indexed path, empty when it cannot be resolved unambiguously
     */
    public static Optional<String> resolvePath(CodebaseIndex index, @Nullable String path) {
        if (path == null) {
            return Optional.empty();
        }
        String normalized = RepoPaths.normalize(path);
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || RepoPaths.hasParentSegment(normalized)) {
            return Optional.empty();
        }
        if (index.containsPath(normalized)) {
            return Optional.of(normalized);
        }
        List<String> suffixed = index.files().stream().filter(f -> f.endsWith("/" + normalized)).toList();
        if (suffixed.size() == 1) {
            return Optional.of(suffixed.getFirst());
        }
        if (suffixed.size() > 1) {
            return Optional.empty();
        }
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (fileName.endsWith(".java") && fileName.length() > ".java".length()) {
            return type(index, fileName.substring(0, fileName.length() - ".java".length())).map(IndexedType::path);
        }
        List<String> named = index.files().stream().filter(f -> f.equals(fileName) || f.endsWith("/" + fileName))
                .toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /**
     * Resolves a component name to an indexed type: the name itself when it is a type name, else the first longest run
     * of consecutive words (up to {@value #MAX_NAME_WORDS}) whose capitalized concatenation is a type name.
     *
     * @param index the index
     * @param name the name, may be prose
     * @return the type, empty when no run names exactly one type
     */
    public static Optional<IndexedType> resolveName(CodebaseIndex index, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String stripped = name.strip();
        Optional<IndexedType> exact = type(index, stripped);
        if (exact.isPresent()) {
            return exact;
        }
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(stripped);
        while (m.find()) {
            words.add(m.group());
        }
        for (int i = 0; i < words.size(); i++) {
            for (int k = Math.min(MAX_NAME_WORDS, words.size() - i); k >= 1; k--) {
                Optional<IndexedType> type = type(index, concat(words.subList(i, i + k)));
                if (type.isPresent()) {
                    return type;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a component given by path and name: the path first ({@link #resolvePath}), then the name
     * ({@link #resolveName}). The name is used only when the path is blank, a {@code *.java} path (every Java file of
     * the tree is indexed, so an unknown one is a wrong path) or a bare identifier without directory or extension;
     * another unknown path (documentation, resources) is never replaced by a type found through the name.
     *
     * @param index the index
     * @param path the path, may be null or blank
     * @param name the name, may be null or prose
     * @return the indexed path, empty when neither resolves
     */
    public static Optional<String> resolve(CodebaseIndex index, @Nullable String path, @Nullable String name) {
        Optional<String> byPath = resolvePath(index, path);
        if (byPath.isPresent()) {
            return byPath;
        }
        String normalized = path == null ? "" : RepoPaths.normalize(path);
        boolean nameApplies = normalized.isEmpty() || normalized.endsWith(".java")
                || (!normalized.contains("/") && !normalized.contains("."));
        return nameApplies ? resolveName(index, name).map(IndexedType::path) : Optional.empty();
    }

    /**
     * Returns the indexed files that a free text (verification feedback, check details, an error message) names, in
     * order of first mention: repository paths ({@link #resolvePath}), {@code *.java} file names, and type names
     * written as identifiers. An identifier is a word that starts with an upper-case letter ({@code Link},
     * {@code LinkService}) or contains one ({@code linkService}); together with the words that follow it on the same
     * line, separated only by spaces, it forms runs of up to {@value #MAX_TEXT_WORDS} words, and the longest run that
     * names a type wins ("Link service" names {@code LinkService}, "Link entity" names {@code Link}). Lower-case prose
     * ("the link") names nothing.
     *
     * @param index the index
     * @param text the text
     * @param max maximum number of paths returned
     * @return indexed paths, at most {@code max}
     */
    public static List<String> mentionedPaths(CodebaseIndex index, @Nullable String text, int max) {
        if (text == null || text.isBlank() || max <= 0 || index.isEmpty()) {
            return List.of();
        }
        List<Mention> mentions = new ArrayList<>();
        Matcher paths = PATH.matcher(text);
        while (paths.find()) {
            int at = paths.start(1);
            resolvePath(index, paths.group(1)).ifPresent(p -> mentions.add(new Mention(at, p)));
        }
        Matcher files = JAVA_FILE.matcher(text);
        while (files.find()) {
            int at = files.start(1);
            type(index, files.group(1)).ifPresent(t -> mentions.add(new Mention(at, t.path())));
        }
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new Word(m.group(), m.start(), m.end()));
        }
        int i = 0;
        while (i < words.size()) {
            Word first = words.get(i);
            if (!identifierLike(first.text())) {
                i++;
                continue;
            }
            int matched = 0;
            for (int k = Math.min(MAX_TEXT_WORDS, words.size() - i); k >= 1 && matched == 0; k--) {
                if (!sameLine(text, words, i, k)) {
                    continue;
                }
                Optional<IndexedType> type = type(index, concat(words.subList(i, i + k).stream().map(Word::text)
                        .toList()));
                if (type.isPresent()) {
                    mentions.add(new Mention(first.start(), type.get().path()));
                    matched = k;
                }
            }
            i += Math.max(1, matched);
        }
        mentions.sort(Comparator.comparingInt(Mention::at));
        Set<String> found = new LinkedHashSet<>();
        for (Mention mention : mentions) {
            if (found.size() >= max) {
                break;
            }
            found.add(mention.path());
        }
        return List.copyOf(found);
    }

    /** Whether a word reads as a Java identifier of a type or variable rather than lower-case prose. */
    private static boolean identifierLike(String word) {
        if (Character.isUpperCase(word.charAt(0))) {
            return true;
        }
        for (int i = 1; i < word.length(); i++) {
            if (Character.isUpperCase(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Whether words {@code i .. i+k-1} are separated only by spaces or tabs (no punctuation, no line break). */
    private static boolean sameLine(String text, List<Word> words, int i, int k) {
        for (int j = i + 1; j < i + k; j++) {
            String gap = text.substring(words.get(j - 1).end(), words.get(j).start());
            if (!WHITESPACE.matcher(gap).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String concat(List<String> words) {
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }

    private record Word(String text, int start, int end) {
    }

    private record Mention(int at, String path) {
    }
}
