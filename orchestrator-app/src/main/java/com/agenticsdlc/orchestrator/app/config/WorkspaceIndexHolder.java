package com.agenticsdlc.orchestrator.app.config;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * Holds the current {@link CodebaseIndex} of the repository workspace and rebuilds it on demand. The engine reads
 * {@link #current()} through a supplier, so a re-index is picked up by every stage that starts afterwards.
 * Thread-safe.
 */
public final class WorkspaceIndexHolder {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceIndexHolder.class);

    private final Path root;
    private final boolean enabled;
    private final CodebaseIndexer indexer;
    private final AtomicReference<@Nullable CodebaseIndex> current = new AtomicReference<>();
    private volatile @Nullable String lastError;

    /**
     * Creates the holder; nothing is indexed until {@link #reindex()} is called.
     *
     * @param root workspace root
     * @param enabled whether indexing is enabled
     * @param indexer the indexer
     */
    public WorkspaceIndexHolder(Path root, boolean enabled, CodebaseIndexer indexer) {
        this.root = root;
        this.enabled = enabled;
        this.indexer = indexer;
    }

    /**
     * Returns the workspace root.
     *
     * @return absolute root
     */
    public Path root() {
        return root;
    }

    /**
     * Returns whether workspace indexing is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the current index.
     *
     * @return the index, or null when indexing is disabled or has not run yet
     */
    public @Nullable CodebaseIndex current() {
        return enabled ? current.get() : null;
    }

    /**
     * Returns the error of the last indexing attempt.
     *
     * @return the error message, or null when the last attempt succeeded
     */
    public @Nullable String lastError() {
        return lastError;
    }

    /**
     * Rebuilds the index from disk. A failure keeps an empty index and records the error.
     *
     * @return the new index ({@link CodebaseIndex#empty()} when disabled or failed)
     */
    public synchronized CodebaseIndex reindex() {
        if (!enabled) {
            return CodebaseIndex.empty();
        }
        CodebaseIndex index;
        long started = System.nanoTime();
        try {
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("workspace root is not a directory: " + root);
            }
            index = indexer.index(root);
            lastError = null;
            LOG.info("workspace indexed root={} types={} endpoints={} migrations={} tookMs={}", root,
                    index.types().size(), index.endpoints().size(), index.migrations().size(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (UncheckedIOException | IllegalStateException e) {
            index = CodebaseIndex.empty();
            lastError = e.getMessage();
            LOG.warn("workspace indexing failed root={} error={}", root, e.getMessage());
        }
        current.set(index);
        return index;
    }
}
