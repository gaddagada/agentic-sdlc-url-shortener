package com.agenticsdlc.orchestrator.core.workspace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Inventory of the Maven build files of a workspace: every {@code pom.xml} under the root (directories in
 * {@link #SKIPPED_DIRECTORIES} and symbolic links are not followed), its module coordinates and every declared
 * dependency and build plugin, plus hygiene flags.
 *
 * <p><strong>This is an inventory and hygiene check, not a vulnerability scan.</strong> It never resolves artifacts,
 * never contacts a repository and knows nothing about CVEs. Finding known vulnerabilities needs a real scanner with
 * network access (for example the OWASP dependency-check Maven plugin, or {@code mvn dependency:tree} fed to a
 * scanner), which the engine can run through its optional dependency scan hook
 * ({@code EngineConfig.dependencyScanCommand}).</p>
 *
 * <p>POMs are parsed with the JDK XML parser configured securely: DOCTYPE declarations are rejected (so no external
 * or internal entities and no entity expansion), external DTDs and schemas are never loaded and XInclude is off. A
 * POM that cannot be parsed that way is reported with an {@link FlagKind#UNPARSEABLE_POM} flag.</p>
 *
 * <p>Versions: properties ({@code ${...}}) are resolved from the module, its parents in the workspace and the
 * {@code project.*} built-ins; a declared version that still contains an unresolved expression (a property of an
 * external parent or BOM, or one set on the command line) is recorded as {@value DependencyRecord#UNKNOWN} rather
 * than as the raw expression. Flags (severity): {@code system} scope and
 * {@code LATEST}/{@code RELEASE} versions HIGH; version ranges and SNAPSHOT versions of artifacts that are not modules
 * of the workspace MEDIUM; duplicate declarations within one section of a module and plugins without a version that
 * nothing in the workspace manages LOW. A plugin without a version whose parent POM lies outside the workspace (for
 * example {@code spring-boot-starter-parent}) is recorded with version {@value DependencyRecord#UNKNOWN} and not
 * flagged: the inventory cannot tell whether that parent manages it.</p>
 *
 * @param modules the POMs read, sorted by path
 * @param dependencies every declared dependency and build plugin, in POM and document order
 * @param flags hygiene flags, in POM and document order
 * @param notes notes about the inventory itself (limits reached, unreadable files)
 */
public record DependencyInventory(List<PomModule> modules, List<DependencyRecord> dependencies,
                                  List<DependencyFlag> flags, List<String> notes) {

    /**
     * Directories that are never searched for POMs: build output ({@code target}, {@code build}, {@code out}), VCS
     * and IDE metadata ({@code .git}, {@code .idea}, {@code .vscode}), dependencies ({@code node_modules}) and
     * runtime data ({@code data}, {@code sandbox}).
     */
    public static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".git", "node_modules", "sandbox", ".idea",
            ".vscode", "data", "build", "out");

    /** Maximum number of POMs read. */
    public static final int MAX_POMS = 500;

    /** Maximum size of one POM in bytes. */
    public static final long MAX_POM_BYTES = 2L * 1024 * 1024;

    /** Default plugin group id. */
    static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_PROPERTY_DEPTH = 10;

    /**
     * Copies the lists.
     */
    public DependencyInventory {
        modules = Immutables.list(modules);
        dependencies = Immutables.list(dependencies);
        flags = Immutables.list(flags);
        notes = Immutables.list(notes);
    }

    /**
     * A Maven module (one POM).
     *
     * @param path repository-relative POM path with forward slashes
     * @param groupId group id (inherited from the parent when not declared)
     * @param artifactId artifact id
     * @param version version (inherited from the parent when not declared)
     * @param parent parent coordinate {@code groupId:artifactId:version}, or null without a parent
     */
    public record PomModule(String path, String groupId, String artifactId, String version, @Nullable String parent) {
        /**
         * Normalizes text.
         */
        public PomModule {
            path = Immutables.text(path);
            groupId = Immutables.text(groupId);
            artifactId = Immutables.text(artifactId);
            version = Immutables.text(version);
        }
    }

    /** Kind of hygiene flag. */
    public enum FlagKind {
        /** A dependency or plugin uses {@code system} scope (HIGH). */
        SYSTEM_SCOPE,
        /** A {@code LATEST} or {@code RELEASE} version (HIGH). */
        DYNAMIC_VERSION,
        /** A version range such as {@code [1.0,2.0)} (MEDIUM). */
        VERSION_RANGE,
        /** A SNAPSHOT version of an artifact that is not a module of the workspace (MEDIUM). */
        SNAPSHOT_VERSION,
        /** The same artifact is declared twice in one section of a module (LOW). */
        DUPLICATE_DECLARATION,
        /** A plugin without a version that nothing in the workspace manages (LOW). */
        UNVERSIONED_PLUGIN,
        /** A POM that cannot be parsed securely (MEDIUM). */
        UNPARSEABLE_POM
    }

    /**
     * A hygiene flag.
     *
     * @param module POM path
     * @param coordinate affected coordinate ({@code groupId:artifactId:version}), empty for a POM-level flag
     * @param kind flag kind
     * @param severity severity
     * @param message explanation
     */
    public record DependencyFlag(String module, String coordinate, FlagKind kind, Severity severity, String message) {
        /**
         * Validates the flag.
         */
        public DependencyFlag {
            module = Immutables.text(module);
            coordinate = Immutables.text(coordinate);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(severity, "severity");
            message = Immutables.text(message);
        }
    }

    /**
     * Returns an inventory without modules.
     *
     * @return the empty inventory
     */
    public static DependencyInventory empty() {
        return new DependencyInventory(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Returns the flags with at least the given severity.
     *
     * @param threshold minimum severity
     * @return the flags
     */
    public List<DependencyFlag> flagsAtLeast(Severity threshold) {
        return flags.stream().filter(f -> f.severity().atLeast(threshold)).toList();
    }

    /**
     * Reads the inventory of a workspace.
     *
     * @param workspaceRoot the workspace (repository) root
     * @return the inventory; a missing or unreadable root yields an empty inventory with a note
     */
    public static DependencyInventory read(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<String> notes = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            notes.add("workspace root " + root.getFileName() + " is not a readable directory; nothing inventoried");
            return new DependencyInventory(List.of(), List.of(), List.of(), notes);
        }
        List<Path> poms = findPoms(root, notes);
        List<ParsedPom> parsed = new ArrayList<>();
        List<DependencyFlag> flags = new ArrayList<>();
        for (Path pom : poms) {
            String path = relative(root, pom);
            try {
                long size = Files.size(pom);
                if (size > MAX_POM_BYTES) {
                    notes.add(path + ": skipped (" + size + " bytes exceed the limit of " + MAX_POM_BYTES + ")");
                    continue;
                }
                byte[] bytes = Files.readAllBytes(pom);
                DocumentBuilder builder = secureBuilder();
                Document document = builder.parse(new ByteArrayInputStream(bytes));
                parsed.add(ParsedPom.of(path, document.getDocumentElement()));
            } catch (SAXException e) {
                flags.add(new DependencyFlag(path, "", FlagKind.UNPARSEABLE_POM, Severity.MEDIUM,
                        "POM could not be parsed securely: " + describe(e)));
            } catch (IOException e) {
                notes.add(path + ": not readable (" + e.getClass().getSimpleName() + ")");
            }
        }
        return analyze(parsed, flags, notes);
    }

    /**
     * Returns a document builder that rejects DOCTYPE declarations, never loads external DTDs, schemas or entities,
     * does not expand entity references and has XInclude disabled; parse errors are thrown, never printed.
     *
     * @return the builder
     * @throws IllegalStateException when the JDK parser does not support the secure configuration
     */
    static DocumentBuilder secureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // ignored: warnings never make a POM unusable
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder;
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("the JDK XML parser does not support a secure configuration", e);
        }
    }

    private static String describe(SAXException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof SAXParseException p && p.getLineNumber() > 0) {
            message = "line " + p.getLineNumber() + ": " + message;
        }
        return message.length() > 300 ? message.substring(0, 297) + "..." : message;
    }

    private static List<Path> findPoms(Path root, List<String> notes) {
        List<Path> poms = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().equals("pom.xml")) {
                        if (poms.size() >= MAX_POMS) {
                            notes.add("more than " + MAX_POMS + " POMs; the rest was not inventoried");
                            return FileVisitResult.TERMINATE;
                        }
                        poms.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    notes.add(relative(root, file) + ": not readable (" + exc.getClass().getSimpleName() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            notes.add("workspace could not be searched for POMs (" + e.getClass().getSimpleName() + ")");
        }
        poms.sort((a, b) -> relative(root, a).compareTo(relative(root, b)));
        return poms;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // =========================================================================================== analysis

    private static DependencyInventory analyze(List<ParsedPom> poms, List<DependencyFlag> parseFlags,
                                               List<String> notes) {
        Map<String, ParsedPom> byKey = new HashMap<>();
        for (ParsedPom pom : poms) {
            byKey.putIfAbsent(pom.groupId() + ":" + pom.artifactId(), pom);
        }
        List<PomModule> modules = new ArrayList<>();
        List<DependencyRecord> records = new ArrayList<>();
        List<DependencyFlag> flags = new ArrayList<>(parseFlags);
        for (ParsedPom pom : poms) {
            List<ParsedPom> ancestors = ancestors(pom, byKey);
            boolean externalParent = hasExternalParent(pom, ancestors, byKey);
            modules.add(new PomModule(pom.path(), pom.groupId(), pom.artifactId(), resolve(pom.version(), pom,
                    ancestors), pom.parentCoordinate()));
            Map<String, Integer> seen = new HashMap<>();
            for (Declaration d : pom.declarations()) {
                String declared = versionOf(d, pom, ancestors, externalParent);
                // an expression the workspace cannot resolve is not a version: record it as unknown, but still
                // flag what is visible of it (a range stays a range)
                String version = declared.contains("${") ? DependencyRecord.UNKNOWN : declared;
                DependencyRecord record = new DependencyRecord(pom.path(), d.groupId(), d.artifactId(), version,
                        d.scope(), d.kind(), d.section());
                records.add(record);
                flag(record, declared, d, byKey, flags);
                String key = d.section() + "|" + d.kind() + "|" + d.groupId() + ":" + d.artifactId() + ":"
                        + d.type() + ":" + d.classifier();
                if (seen.merge(key, 1, Integer::sum) == 2) {
                    flags.add(new DependencyFlag(pom.path(), record.coordinate(), FlagKind.DUPLICATE_DECLARATION,
                            Severity.LOW, d.groupId() + ":" + d.artifactId() + " is declared more than once in "
                            + d.section() + "; Maven keeps only one of the declarations"));
                }
            }
        }
        return new DependencyInventory(modules, records, flags, notes);
    }

    private static void flag(DependencyRecord record, String version, Declaration d, Map<String, ParsedPom> modules,
                             List<DependencyFlag> flags) {
        String what = (d.kind() == DependencyRecord.Kind.PLUGIN ? "plugin " : "dependency ") + d.groupId() + ":"
                + d.artifactId();
        if ("system".equalsIgnoreCase(d.scope())) {
            flags.add(new DependencyFlag(record.module(), record.coordinate(), FlagKind.SYSTEM_SCOPE, Severity.HIGH,
                    what + " uses system scope: a local file outside the repository, invisible to dependency "
                            + "management and scanners"));
        }
        if ("LATEST".equalsIgnoreCase(version) || "RELEASE".equalsIgnoreCase(version)) {
            flags.add(new DependencyFlag(record.module(), record.coordinate(), FlagKind.DYNAMIC_VERSION,
                    Severity.HIGH, what + " uses the dynamic version " + version
                    + ": builds are not reproducible and any newly published release is pulled in unreviewed"));
        } else if (version.startsWith("[") || version.startsWith("(")) {
            flags.add(new DependencyFlag(record.module(), record.coordinate(), FlagKind.VERSION_RANGE,
                    Severity.MEDIUM, what + " uses the version range " + version + ": builds are not reproducible"));
        } else if (version.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT")
                && !modules.containsKey(d.groupId() + ":" + d.artifactId())) {
            flags.add(new DependencyFlag(record.module(), record.coordinate(), FlagKind.SNAPSHOT_VERSION,
                    Severity.MEDIUM, what + " uses the SNAPSHOT version " + version
                    + " of an artifact that is not built in this workspace: its content can change without notice"));
        }
        if (DependencyRecord.NONE.equals(version)) {
            flags.add(new DependencyFlag(record.module(), record.coordinate(), FlagKind.UNVERSIONED_PLUGIN,
                    Severity.LOW, what + " has no version and nothing in the workspace manages it: Maven picks a "
                    + "default that depends on the Maven version"));
        }
    }

    private static String versionOf(Declaration d, ParsedPom pom, List<ParsedPom> ancestors, boolean externalParent) {
        if (!d.version().isBlank()) {
            return resolve(d.version(), pom, ancestors);
        }
        if (d.kind() == DependencyRecord.Kind.DEPENDENCY) {
            return DependencyRecord.MANAGED; // Maven fails the build for an unmanaged dependency without a version
        }
        String key = d.groupId() + ":" + d.artifactId();
        if (pom.managesPlugin(key, d.section())) {
            return DependencyRecord.MANAGED;
        }
        for (ParsedPom ancestor : ancestors) {
            if (ancestor.declaresPluginVersion(key)) {
                return DependencyRecord.MANAGED;
            }
        }
        return externalParent ? DependencyRecord.UNKNOWN : DependencyRecord.NONE;
    }

    /** Returns the parents of a POM that are POMs of the workspace, nearest first (stops at an external parent). */
    private static List<ParsedPom> ancestors(ParsedPom pom, Map<String, ParsedPom> byKey) {
        List<ParsedPom> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(pom.path());
        ParsedPom current = pom;
        while (current.parentKey() != null) {
            ParsedPom parent = byKey.get(current.parentKey());
            if (parent == null || !visited.add(parent.path())) {
                break;
            }
            chain.add(parent);
            current = parent;
        }
        return chain;
    }

    /** Returns whether the parent chain of a POM reaches a parent that is not a POM of the workspace. */
    private static boolean hasExternalParent(ParsedPom pom, List<ParsedPom> ancestors, Map<String, ParsedPom> byKey) {
        ParsedPom last = ancestors.isEmpty() ? pom : ancestors.getLast();
        return last.parentKey() != null && !byKey.containsKey(last.parentKey());
    }

    /** Resolves {@code ${...}} expressions; unknown properties are kept verbatim. */
    static String resolve(String value, ParsedPom pom, List<ParsedPom> ancestors) {
        String current = value;
        for (int depth = 0; depth < MAX_PROPERTY_DEPTH && current.contains("${"); depth++) {
            Matcher m = PROPERTY.matcher(current);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String replacement = property(m.group(1).strip(), pom, ancestors);
                if (replacement != null) {
                    changed = true;
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : m.group()));
            }
            m.appendTail(out);
            current = out.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private static @Nullable String property(String name, ParsedPom pom, List<ParsedPom> ancestors) {
        switch (name) {
            case "project.version", "pom.version", "version" -> {
                return pom.version().isBlank() ? null : pom.version();
            }
            case "project.groupId", "pom.groupId" -> {
                return pom.groupId().isBlank() ? null : pom.groupId();
            }
            case "project.artifactId", "pom.artifactId" -> {
                return pom.artifactId().isBlank() ? null : pom.artifactId();
            }
            case "project.parent.version", "parent.version" -> {
                return pom.parentVersion();
            }
            default -> {
                String own = pom.properties().get(name);
                if (own != null) {
                    return own;
                }
                for (ParsedPom ancestor : ancestors) {
                    String inherited = ancestor.properties().get(name);
                    if (inherited != null) {
                        return inherited;
                    }
                }
                return null;
            }
        }
    }

    // =========================================================================================== parsing

    /** One declared dependency or plugin. */
    record Declaration(DependencyRecord.Kind kind, String section, String groupId, String artifactId,
                               String version, String scope, String type, String classifier) {
    }

    /** The parts of a POM the inventory uses. */
    record ParsedPom(String path, String groupId, String artifactId, String version, @Nullable String parentGroupId,
                     @Nullable String parentArtifactId, @Nullable String parentVersion, Map<String, String> properties,
                     List<Declaration> declarations) {

        static ParsedPom of(String path, Element project) {
            Element parent = child(project, "parent");
            String parentGroup = parent == null ? null : text(child(parent, "groupId"));
            String parentArtifact = parent == null ? null : text(child(parent, "artifactId"));
            String parentVersion = parent == null ? null : text(child(parent, "version"));
            String groupId = text(child(project, "groupId"));
            String version = text(child(project, "version"));
            Map<String, String> properties = new LinkedHashMap<>();
            Element props = child(project, "properties");
            if (props != null) {
                for (Element property : children(props)) {
                    properties.put(property.getLocalName(), text(property));
                }
            }
            List<Declaration> declarations = new ArrayList<>();
            collectSections(project, "", declarations);
            Element profiles = child(project, "profiles");
            if (profiles != null) {
                for (Element profile : children(profiles, "profile")) {
                    String id = text(child(profile, "id"));
                    collectSections(profile, "profile:" + (id.isBlank() ? "?" : id) + "/", declarations);
                }
            }
            return new ParsedPom(path, groupId.isBlank() && parentGroup != null ? parentGroup : groupId,
                    text(child(project, "artifactId")),
                    version.isBlank() && parentVersion != null ? parentVersion : version,
                    blankToNull(parentGroup), blankToNull(parentArtifact), blankToNull(parentVersion), properties,
                    declarations);
        }

        @Nullable String parentKey() {
            return parentGroupId == null || parentArtifactId == null ? null : parentGroupId + ":" + parentArtifactId;
        }

        @Nullable String parentCoordinate() {
            return parentKey() == null ? null : parentKey() + ":" + (parentVersion == null ? "" : parentVersion);
        }

        /** Whether this POM's plugin management (of the same profile, or the main one) manages a plugin. */
        boolean managesPlugin(String key, String section) {
            String prefix = section.startsWith("profile:") ? section.substring(0, section.indexOf('/') + 1) : "";
            return declarations.stream().anyMatch(d -> d.kind() == DependencyRecord.Kind.PLUGIN
                    && (d.groupId() + ":" + d.artifactId()).equals(key) && !d.version().isBlank()
                    && (d.section().equals("build/pluginManagement")
                    || d.section().equals(prefix + "build/pluginManagement")));
        }

        /** Whether this POM declares a plugin with a version in its main build (plugins or plugin management). */
        boolean declaresPluginVersion(String key) {
            return declarations.stream().anyMatch(d -> d.kind() == DependencyRecord.Kind.PLUGIN
                    && (d.groupId() + ":" + d.artifactId()).equals(key) && !d.version().isBlank()
                    && !d.section().startsWith("profile:"));
        }

        private static void collectSections(Element base, String prefix, List<Declaration> out) {
            Element dependencies = child(base, "dependencies");
            if (dependencies != null) {
                collectDependencies(dependencies, prefix + "dependencies", out);
            }
            Element management = child(base, "dependencyManagement");
            Element managed = management == null ? null : child(management, "dependencies");
            if (managed != null) {
                collectDependencies(managed, prefix + "dependencyManagement", out);
            }
            Element build = child(base, "build");
            if (build != null) {
                Element plugins = child(build, "plugins");
                if (plugins != null) {
                    collectPlugins(plugins, prefix + "build/plugins", out);
                }
                Element pluginManagement = child(build, "pluginManagement");
                Element managedPlugins = pluginManagement == null ? null : child(pluginManagement, "plugins");
                if (managedPlugins != null) {
                    collectPlugins(managedPlugins, prefix + "build/pluginManagement", out);
                }
            }
        }

        private static void collectDependencies(Element dependencies, String section, List<Declaration> out) {
            for (Element dependency : children(dependencies, "dependency")) {
                String scope = text(child(dependency, "scope"));
                String type = text(child(dependency, "type"));
                out.add(new Declaration(DependencyRecord.Kind.DEPENDENCY, section, text(child(dependency, "groupId")),
                        text(child(dependency, "artifactId")), text(child(dependency, "version")),
                        scope.isBlank() ? "compile" : scope, type.isBlank() ? "jar" : type,
                        text(child(dependency, "classifier"))));
            }
        }

        private static void collectPlugins(Element plugins, String section, List<Declaration> out) {
            for (Element plugin : children(plugins, "plugin")) {
                String groupId = text(child(plugin, "groupId"));
                out.add(new Declaration(DependencyRecord.Kind.PLUGIN, section,
                        groupId.isBlank() ? DEFAULT_PLUGIN_GROUP : groupId, text(child(plugin, "artifactId")),
                        text(child(plugin, "version")), "build", "maven-plugin", ""));
            }
        }

        private static @Nullable String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static @Nullable Element child(Element parent, String name) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getLocalName())) {
                    return element;
                }
            }
            return null;
        }

        private static List<Element> children(Element parent) {
            List<Element> result = new ArrayList<>();
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    result.add(element);
                }
            }
            return result;
        }

        private static List<Element> children(Element parent, String name) {
            return children(parent).stream().filter(e -> name.equals(e.getLocalName())).toList();
        }

        private static String text(@Nullable Element element) {
            return element == null ? "" : element.getTextContent().strip();
        }
    }
}
