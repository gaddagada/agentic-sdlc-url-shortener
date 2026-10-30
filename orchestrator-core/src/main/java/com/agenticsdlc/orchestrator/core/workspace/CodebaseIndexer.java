package com.agenticsdlc.orchestrator.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link CodebaseIndex} by walking a workspace (skipping {@code target}, {@code .git},
 * {@code node_modules}, {@code data}, {@code sandbox} and {@code .idea}). Java files under
 * {@code src/main/java} and {@code src/test/java} are classified with regular expressions (comments are
 * stripped first); Flyway migrations and {@code application*.yml|yaml|properties} files are recorded as
 * resources. The indexer only reads files.
 */
public final class CodebaseIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(CodebaseIndexer.class);

    /** Directory names that are never indexed. */
    public static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", "node_modules", "data", "sandbox",
            ".idea");

    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern CONTROLLER = Pattern.compile("@(RestController|Controller)\\b");
    private static final Pattern SERVICE = Pattern.compile("@Service\\b");
    private static final Pattern REPOSITORY = Pattern.compile(
            "@Repository\\b|\\bextends\\s+(Jpa|Crud|ListCrud)Repository\\s*<");
    private static final Pattern ENTITY = Pattern.compile("@Entity\\b");
    private static final Pattern CONFIG = Pattern.compile("@Configuration\\b|@ConfigurationProperties\\b");
    private static final Pattern FILTER = Pattern.compile(
            "\\bextends\\s+OncePerRequestFilter\\b|\\bimplements\\s+[\\w.,\\s<>]*\\bFilter\\b");
    private static final Pattern TABLE = Pattern.compile("@Table\\s*\\([^)]*\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(\\w+)");
    private static final Pattern NAMED_ARGUMENT = Pattern.compile("\\b(value|path)\\s*=\\s*");
    private static final Pattern OTHER_ARGUMENT = Pattern.compile("^\\s*[A-Za-z_]\\w*\\s*=");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "\\bstatic\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern TYPE_REFERENCE = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_");
    private static final Pattern MIGRATION = Pattern.compile("(^|.*/)src/main/resources/db/migration/[^/]+\\.sql$");
    private static final Pattern CONFIG_FILE = Pattern.compile(
            "(^|.*/)src/main/resources/application[^/]*\\.(yml|yaml|properties)$");
    private static final Pattern FLYWAY_VERSION = Pattern.compile("V(\\d+(?:[._]\\d+)*)__");
    private static final Set<String> NOISE = Set.of("public", "private", "protected", "static", "final", "class",
            "interface", "record", "enum", "import", "package", "return", "void", "new", "this", "null", "true",
            "false", "throw", "throws", "try", "catch", "finally", "else", "for", "while", "switch", "case",
            "default", "break", "continue", "extends", "implements", "super", "instanceof", "var", "int", "long",
            "boolean", "double", "float", "char", "byte", "short", "string", "list", "map", "set", "optional",
            "override", "java", "util", "org", "com", "lang", "annotation", "springframework", "jakarta", "object",
            "get", "the", "and", "not", "assert", "assertions", "assertj", "junit", "jupiter", "api");

    private final Clock clock;

    /**
     * Creates an indexer using the system UTC clock.
     */
    public CodebaseIndexer() {
        this(Clock.systemUTC());
    }

    /**
     * Creates an indexer.
     *
     * @param clock clock for the index timestamp
     */
    public CodebaseIndexer(Clock clock) {
        this.clock = clock;
    }

    /**
     * Indexes a workspace.
     *
     * @param root workspace root
     * @return the index
     * @throws UncheckedIOException when the workspace cannot be read
     */
    public CodebaseIndex index(Path root) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        List<String> files = listFiles(absoluteRoot);
        List<ParsedType> parsed = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        List<String> configs = new ArrayList<>();
        for (String relative : files) {
            if (relative.endsWith(".java") && (isUnder(relative, "src/main/java/") || isUnder(relative, "src/test/java/"))) {
                String source = read(absoluteRoot.resolve(relative));
                if (source != null) {
                    parsed.add(parse(relative, source));
                }
            } else if (MIGRATION.matcher(relative).matches()) {
                migrations.add(relative);
            } else if (CONFIG_FILE.matcher(relative).matches()) {
                configs.add(relative);
            }
        }
        migrations.sort(MIGRATION_ORDER);
        Set<String> knownNames = new HashSet<>();
        parsed.forEach(p -> knownNames.add(p.simpleName));
        Map<String, String> constants = new HashMap<>();
        for (ParsedType p : parsed) {
            Matcher c = STRING_CONSTANT.matcher(p.code);
            while (c.find()) {
                constants.put(p.simpleName + "." + c.group(1), c.group(2));
            }
        }
        List<IndexedType> types = new ArrayList<>();
        Map<String, Set<String>> tokens = new LinkedHashMap<>();
        for (ParsedType p : parsed) {
            Set<String> references = new TreeSet<>();
            Matcher m = TYPE_REFERENCE.matcher(p.blanked);
            while (m.find()) {
                String name = m.group();
                if (!name.equals(p.simpleName) && knownNames.contains(name)) {
                    references.add(name);
                }
            }
            List<Endpoint> endpoints = p.kind == TypeKind.CONTROLLER ? endpoints(p.code, p.simpleName, constants)
                    : List.of();
            types.add(new IndexedType(p.fqcn, p.simpleName, p.path, p.module, p.kind, endpoints, p.table,
                    references, p.test));
            tokens.put(p.path, p.tokens);
        }
        String rootName = absoluteRoot.getFileName() == null ? absoluteRoot.toString()
                : absoluteRoot.getFileName().toString();
        LOG.info("workspace indexed root={} types={} migrations={} configs={}", rootName, types.size(),
                migrations.size(), configs.size());
        return new CodebaseIndex(rootName, absoluteRoot, clock.instant().truncatedTo(ChronoUnit.MILLIS), types,
                migrations, configs, tokens);
    }

    private static final Comparator<String> MIGRATION_ORDER = (a, b) -> {
        List<Integer> va = flywayVersion(a);
        List<Integer> vb = flywayVersion(b);
        for (int i = 0; i < Math.min(va.size(), vb.size()); i++) {
            int c = Integer.compare(va.get(i), vb.get(i));
            if (c != 0) {
                return c;
            }
        }
        int c = Integer.compare(va.size(), vb.size());
        return c != 0 ? c : a.compareTo(b);
    };

    /**
     * Returns the numeric Flyway version parts of a migration file name ({@code V2_1__x.sql} gives [2, 1]).
     *
     * @param path migration path
     * @return version parts, empty when the name has no version
     */
    static List<Integer> flywayVersion(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        Matcher m = FLYWAY_VERSION.matcher(name);
        if (!m.find()) {
            return List.of();
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : m.group(1).split("[._]")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }
        return parts;
    }

    private static boolean isUnder(String relative, String marker) {
        return relative.startsWith(marker) || relative.contains("/" + marker);
    }

    private static List<String> listFiles(Path root) {
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && dir.getFileName() != null
                            && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.warn("skipping unreadable file path={} error={}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot index workspace " + root, e);
        }
        files.sort(String::compareTo);
        return files;
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readString(file, StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                LOG.warn("skipping unreadable file path={} error={}", file, ex.getMessage());
                return null;
            }
        } catch (IOException e) {
            LOG.warn("skipping unreadable file path={} error={}", file, e.getMessage());
            return null;
        }
    }

    private static ParsedType parse(String relative, String source) {
        String code = stripComments(source);
        String blanked = blankStrings(code);
        String fileName = relative.substring(relative.lastIndexOf('/') + 1);
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());
        Matcher pkg = PACKAGE.matcher(code);
        String fqcn = pkg.find() ? pkg.group(1) + "." + simpleName : simpleName;
        String module = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "";
        boolean test = isUnder(relative, "src/test/java/");
        TypeKind kind = classify(blanked, test);
        String table = null;
        if (kind == TypeKind.ENTITY) {
            Matcher t = TABLE.matcher(code);
            table = t.find() ? t.group(1) : snakeCase(simpleName);
        }
        return new ParsedType(fqcn, simpleName, relative, module, kind, table, test, code, blanked, tokens(code));
    }

    /**
     * Classifies a type by its (comment-free) source.
     *
     * @param code source without comments
     * @param test whether the file is under {@code src/test}
     * @return the kind
     */
    static TypeKind classify(String code, boolean test) {
        if (test) {
            return TypeKind.TEST;
        }
        if (CONTROLLER.matcher(code).find()) {
            return TypeKind.CONTROLLER;
        }
        if (SERVICE.matcher(code).find()) {
            return TypeKind.SERVICE;
        }
        if (REPOSITORY.matcher(code).find()) {
            return TypeKind.REPOSITORY;
        }
        if (ENTITY.matcher(code).find()) {
            return TypeKind.ENTITY;
        }
        if (CONFIG.matcher(code).find()) {
            return TypeKind.CONFIG;
        }
        if (FILTER.matcher(code).find()) {
            return TypeKind.FILTER;
        }
        return TypeKind.COMPONENT;
    }

    /**
     * Extracts endpoints: a class-level {@code @RequestMapping} prefix plus method-level mappings.
     *
     * @param code source without comments
     * @param simpleName controller simple name
     * @param constants string constants by {@code SimpleName.NAME}, used to resolve paths such as
     *                  {@code @RequestMapping(LinkController.BASE_PATH)}
     * @return endpoints in source order
     */
    static List<Endpoint> endpoints(String code, String simpleName, Map<String, String> constants) {
        Matcher decl = Pattern.compile("\\b(class|record|interface)\\s+" + Pattern.quote(simpleName) + "\\b")
                .matcher(code);
        int declaration = decl.find() ? decl.start() : 0;
        String prefix = "";
        List<Endpoint> endpoints = new ArrayList<>();
        Matcher m = MAPPING.matcher(code);
        while (m.find()) {
            int argsStart = skipWhitespace(code, m.end());
            String args = "";
            int end = m.end();
            if (argsStart < code.length() && code.charAt(argsStart) == '(') {
                int close = matchingParen(code, argsStart);
                args = code.substring(argsStart + 1, close);
                end = close + 1;
            }
            String path = pathOf(args, simpleName, constants);
            if (m.start() < declaration) {
                if ("Request".equals(m.group(1))) {
                    prefix = path;
                }
                continue;
            }
            String method;
            if ("Request".equals(m.group(1))) {
                Matcher rm = REQUEST_METHOD.matcher(args);
                method = rm.find() ? rm.group(1).toUpperCase(Locale.ROOT) : "ANY";
            } else {
                method = m.group(1).toUpperCase(Locale.ROOT);
            }
            endpoints.add(new Endpoint(method, joinPaths(prefix, path), simpleName, handlerName(code, end)));
        }
        return endpoints;
    }

    /**
     * Resolves the path of a mapping annotation's arguments: a {@code value}/{@code path} argument or the
     * positional argument, which may be a string literal, a string constant ({@code BASE_PATH} or
     * {@code LinkController.BASE_PATH}), a concatenation of both, or an array (first element).
     */
    static String pathOf(String args, String owner, Map<String, String> constants) {
        String expression;
        Matcher named = NAMED_ARGUMENT.matcher(args);
        if (named.find()) {
            expression = firstTopLevel(args.substring(named.end()));
        } else if (!OTHER_ARGUMENT.matcher(args).find()) {
            expression = firstTopLevel(args);
        } else {
            return "";
        }
        String trimmed = expression.strip();
        if (trimmed.startsWith("{")) {
            trimmed = firstTopLevel(trimmed.substring(1).replaceFirst("}\\s*$", ""));
        }
        StringBuilder path = new StringBuilder();
        for (String part : trimmed.split("\\+")) {
            String p = part.strip();
            if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
                path.append(p, 1, p.length() - 1);
            } else if (p.matches("[A-Za-z_][\\w.]*")) {
                String value = p.contains(".") ? constants.get(p) : constants.get(owner + "." + p);
                if (value != null) {
                    path.append(value);
                }
            }
        }
        return path.toString();
    }

    /** Returns the text up to the first comma that is not inside quotes, braces or parentheses. */
    private static String firstTopLevel(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '(') {
                depth++;
            } else if (c == '}' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    /**
     * Replaces the contents of string, character and text-block literals with spaces (quotes and line breaks
     * are kept), so that annotations or type names inside literals do not influence classification or
     * references.
     *
     * @param code source without comments
     * @return source with blanked literals
     */
    static String blankStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (code.startsWith("\"\"\"", i)) {
                int close = code.indexOf("\"\"\"", i + 3);
                int end = close < 0 ? n : close;
                out.append("\"\"\"");
                for (int j = i + 3; j < end; j++) {
                    out.append(code.charAt(j) == '\n' ? '\n' : ' ');
                }
                if (close >= 0) {
                    out.append("\"\"\"");
                    i = close + 3;
                } else {
                    i = n;
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = code.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == c || d == '\n') {
                        out.append(d);
                        break;
                    }
                    out.append(' ');
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static String joinPaths(String prefix, String path) {
        String joined = normalizeVariables(("/" + prefix + "/" + path).replaceAll("/+", "/"));
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    /** Rewrites {@code {code:[A-Za-z]{4,32}}} path variables with regular expressions to {@code {code}}. */
    static String normalizeVariables(String path) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < path.length() && path.charAt(j) != ':' && path.charAt(j) != '}') {
                j++;
            }
            out.append('{').append(path, i + 1, Math.min(j, path.length())).append('}');
            int depth = 1;
            int k = j;
            while (k < path.length() && depth > 0) {
                char d = path.charAt(k);
                if (d == '{') {
                    depth++;
                } else if (d == '}') {
                    depth--;
                }
                k++;
            }
            i = k;
        }
        return out.toString();
    }

    private static String handlerName(String code, int from) {
        int i = skipWhitespace(code, from);
        while (i < code.length() && code.charAt(i) == '@') {
            i++;
            while (i < code.length() && (Character.isJavaIdentifierPart(code.charAt(i)) || code.charAt(i) == '.')) {
                i++;
            }
            i = skipWhitespace(code, i);
            if (i < code.length() && code.charAt(i) == '(') {
                i = matchingParen(code, i) + 1;
            }
            i = skipWhitespace(code, i);
        }
        int paren = code.indexOf('(', i);
        if (paren < 0) {
            return "";
        }
        int j = paren - 1;
        while (j >= i && Character.isWhitespace(code.charAt(j))) {
            j--;
        }
        int endName = j + 1;
        while (j >= i && Character.isJavaIdentifierPart(code.charAt(j))) {
            j--;
        }
        return code.substring(j + 1, endName);
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return s.length() - 1;
    }

    /**
     * Removes line and block comments while keeping string, character and text-block literals intact and
     * line breaks in place.
     *
     * @param src Java source
     * @return source without comments
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == c || d == '\n') {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Returns lowercase identifier tokens of the source (whole identifiers and camel-case parts), without
     * Java keywords and very short tokens.
     *
     * @param code source without comments
     * @return tokens
     */
    static Set<String> tokens(String code) {
        Set<String> tokens = new TreeSet<>();
        Matcher m = IDENTIFIER.matcher(code);
        while (m.find()) {
            String identifier = m.group();
            addToken(tokens, identifier.toLowerCase(Locale.ROOT));
            for (String part : CAMEL_SPLIT.split(identifier)) {
                addToken(tokens, part.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static void addToken(Set<String> tokens, String token) {
        if (token.length() >= 3 && !NOISE.contains(token)) {
            tokens.add(token);
        }
    }

    /**
     * Converts {@code ClickEvent} to {@code click_event}.
     *
     * @param name simple name
     * @return snake case
     */
    static String snakeCase(String name) {
        return CAMEL_SPLIT.matcher(name).replaceAll("_").toLowerCase(Locale.ROOT);
    }

    private record ParsedType(String fqcn, String simpleName, String path, String module, TypeKind kind,
                              @Nullable String table, boolean test, String code, String blanked,
                              Set<String> tokens) {
    }
}
