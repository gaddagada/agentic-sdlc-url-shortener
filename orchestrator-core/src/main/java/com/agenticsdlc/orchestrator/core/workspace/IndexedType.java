package com.agenticsdlc.orchestrator.core.workspace;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * An indexed Java type.
 *
 * @param fqcn fully qualified class name
 * @param simpleName simple name
 * @param path workspace-relative path with forward slashes
 * @param module first path segment (Maven module)
 * @param kind detected kind
 * @param endpoints endpoints (controllers only)
 * @param table table name (entities only)
 * @param references simple names of other indexed types used by this type (sorted)
 * @param test whether the type is under {@code src/test}
 */
public record IndexedType(String fqcn, String simpleName, String path, String module, TypeKind kind,
                          List<Endpoint> endpoints, @Nullable String table, Set<String> references, boolean test) {

    /**
     * Copies collections (references become an immutable sorted set).
     */
    public IndexedType {
        endpoints = Immutables.list(endpoints);
        references = Immutables.sortedSet(references);
    }
}
