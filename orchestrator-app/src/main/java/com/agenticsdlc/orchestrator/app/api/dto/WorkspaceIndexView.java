package com.agenticsdlc.orchestrator.app.api.dto;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.Endpoint;
import com.agenticsdlc.orchestrator.core.workspace.IndexedType;

/**
 * The codebase index the brownfield reasoning works on.
 *
 * @param root workspace root
 * @param enabled whether workspace indexing is enabled
 * @param indexedAt index time, or null when not indexed
 * @param indexedFiles number of indexed files
 * @param error error of the last indexing attempt, or null
 * @param counts number of types per module and kind
 * @param types indexed types
 * @param endpoints HTTP endpoints
 * @param migrations database migration paths, oldest first
 */
public record WorkspaceIndexView(String root, boolean enabled, @Nullable Instant indexedAt, int indexedFiles,
                                 @Nullable String error, Map<String, Map<String, Integer>> counts,
                                 List<TypeView> types, List<EndpointView> endpoints, List<String> migrations) {

    /**
     * Maps a codebase index.
     *
     * @param root workspace root
     * @param enabled whether indexing is enabled
     * @param index the index, or null when not indexed
     * @param error error of the last indexing attempt, or null
     * @return the view
     */
    public static WorkspaceIndexView from(Path root, boolean enabled, @Nullable CodebaseIndex index,
                                          @Nullable String error) {
        if (index == null) {
            return new WorkspaceIndexView(root.toString(), enabled, null, 0, error, Map.of(), List.of(), List.of(),
                    List.of());
        }
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        index.summary().forEach((module, byKind) -> {
            Map<String, Integer> kinds = new LinkedHashMap<>();
            byKind.forEach((kind, n) -> kinds.put(kind.name(), n));
            counts.put(module, kinds);
        });
        return new WorkspaceIndexView(root.toString(), enabled, index.indexedAt(), index.indexedFiles(), error,
                counts, index.types().stream().map(TypeView::from).toList(),
                index.endpoints().stream().map(EndpointView::from).toList(), index.migrations());
    }

    /**
     * An indexed type.
     *
     * @param fqcn fully qualified name
     * @param kind detected kind
     * @param path path relative to the root
     * @param module first path segment
     * @param table mapped table (entities), or null
     * @param test whether it is a test type
     * @param endpoints endpoints declared by the type
     * @param references number of other indexed types it references
     */
    public record TypeView(String fqcn, String kind, String path, String module, @Nullable String table,
                           boolean test, List<EndpointView> endpoints, int references) {

        static TypeView from(IndexedType type) {
            return new TypeView(type.fqcn(), type.kind().name(), type.path(), type.module(), type.table(),
                    type.test(), type.endpoints().stream().map(EndpointView::from).toList(),
                    type.references().size());
        }
    }

    /**
     * An HTTP endpoint.
     *
     * @param method HTTP method
     * @param path path
     * @param controller declaring controller
     * @param handler handler method
     */
    public record EndpointView(String method, String path, String controller, String handler) {

        static EndpointView from(Endpoint endpoint) {
            return new EndpointView(endpoint.method(), endpoint.path(), endpoint.controller(), endpoint.handler());
        }
    }
}
