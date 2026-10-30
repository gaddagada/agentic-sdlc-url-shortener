package com.agenticsdlc.orchestrator.core.workspace;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Immutable index of a workspace's Java types, migrations and configuration files, produced by
 * {@link CodebaseIndexer}. Thread-safe.
 */
public final class CodebaseIndex {

    private final String rootName;
    private final @Nullable Path root;
    private final Instant indexedAt;
    private final List<IndexedType> types;
    private final List<String> migrations;
    private final List<String> configFiles;
    private final Map<String, Set<String>> contentTokens;

    /**
     * Creates an index.
     *
     * @param rootName workspace directory name (used in artifacts instead of the absolute path)
     * @param root workspace root, or null for synthetic indexes
     * @param indexedAt index time
     * @param types indexed types, sorted by path
     * @param migrations migration paths, oldest first
     * @param configFiles configuration file paths
     * @param contentTokens identifier tokens per type path (lowercase, sorted)
     */
    public CodebaseIndex(String rootName, @Nullable Path root, Instant indexedAt, List<IndexedType> types,
                         List<String> migrations, List<String> configFiles, Map<String, Set<String>> contentTokens) {
        this.rootName = rootName;
        this.root = root;
        this.indexedAt = indexedAt;
        this.types = List.copyOf(types);
        this.migrations = List.copyOf(migrations);
        this.configFiles = List.copyOf(configFiles);
        Map<String, Set<String>> tokens = new LinkedHashMap<>();
        contentTokens.forEach((k, v) -> tokens.put(k, Immutables.sortedSet(v)));
        this.contentTokens = Collections.unmodifiableMap(tokens);
    }

    /**
     * Returns an empty index.
     *
     * @return empty index
     */
    public static CodebaseIndex empty() {
        return new CodebaseIndex("none", null, Instant.EPOCH, List.of(), List.of(), List.of(), Map.of());
    }

    /** @return the workspace directory name */
    public String rootName() {
        return rootName;
    }

    /** @return the workspace root, or null */
    public @Nullable Path root() {
        return root;
    }

    /** @return when the index was built */
    public Instant indexedAt() {
        return indexedAt;
    }

    /** @return all indexed types, sorted by path */
    public List<IndexedType> types() {
        return types;
    }

    /**
     * Returns the types of a kind.
     *
     * @param kind kind
     * @return matching types
     */
    public List<IndexedType> byKind(TypeKind kind) {
        return types.stream().filter(t -> t.kind() == kind).toList();
    }

    /**
     * Finds a type by simple name (production types take precedence over tests).
     *
     * @param simpleName simple name
     * @return the type
     */
    public Optional<IndexedType> find(String simpleName) {
        return types.stream().filter(t -> t.simpleName().equals(simpleName))
                .min((a, b) -> Boolean.compare(a.test(), b.test()));
    }

    /**
     * Returns the types that reference {@code simpleName}.
     *
     * @param simpleName simple name
     * @return dependents
     */
    public List<IndexedType> dependentsOf(String simpleName) {
        return types.stream().filter(t -> t.references().contains(simpleName)).toList();
    }

    /** @return all endpoints of all controllers */
    public List<Endpoint> endpoints() {
        return types.stream().flatMap(t -> t.endpoints().stream()).toList();
    }

    /** @return migration paths, oldest first */
    public List<String> migrations() {
        return migrations;
    }

    /** @return configuration file paths */
    public List<String> configFiles() {
        return configFiles;
    }

    /**
     * Returns the identifier tokens of a type's source.
     *
     * @param path type path
     * @return tokens (lowercase)
     */
    public Set<String> contentTokens(String path) {
        return contentTokens.getOrDefault(path, Set.of());
    }

    /** @return number of indexed files (types, migrations and configuration files) */
    public int indexedFiles() {
        return types.size() + migrations.size() + configFiles.size();
    }

    /** @return whether nothing was indexed */
    public boolean isEmpty() {
        return types.isEmpty();
    }

    /**
     * Returns counts per module and kind (migrations and configuration files count as RESOURCE).
     *
     * @return module to kind to count, sorted by module
     */
    public Map<String, Map<TypeKind, Integer>> summary() {
        Map<String, Map<TypeKind, Integer>> summary = new TreeMap<>();
        for (IndexedType type : types) {
            summary.computeIfAbsent(type.module(), k -> new EnumMap<>(TypeKind.class)).merge(type.kind(), 1,
                    Integer::sum);
        }
        for (String resource : concat(migrations, configFiles)) {
            String module = resource.contains("/") ? resource.substring(0, resource.indexOf('/')) : "";
            summary.computeIfAbsent(module, k -> new EnumMap<>(TypeKind.class)).merge(TypeKind.RESOURCE, 1,
                    Integer::sum);
        }
        Map<String, Map<TypeKind, Integer>> frozen = new TreeMap<>();
        summary.forEach((k, v) -> frozen.put(k, Collections.unmodifiableMap(v)));
        return Collections.unmodifiableMap(frozen);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
