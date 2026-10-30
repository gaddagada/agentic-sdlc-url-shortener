package com.agenticsdlc.orchestrator.core.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;

/**
 * A disposable copy of the workspace ({@code <sandboxRoot>/<runId>}) into which a change set is
 * materialized. All writes and deletes are confined to the sandbox directory.
 */
public final class WorkspaceSandbox {

    /** Compensation action that deletes a sandbox directory. */
    public static final String DELETE_ACTION = "DELETE_SANDBOX";

    private final Path path;

    private WorkspaceSandbox(Path path) {
        this.path = path;
    }

    /**
     * Returns the sandbox path of a run.
     *
     * @param sandboxRoot sandbox root directory
     * @param runId run id
     * @return the absolute, normalized sandbox path
     */
    public static Path pathFor(Path sandboxRoot, String runId) {
        String safe = runId.replaceAll("[^A-Za-z0-9._-]", "_");
        return sandboxRoot.toAbsolutePath().normalize().resolve(safe).normalize();
    }

    /**
     * Copies the workspace (skipping build output, VCS and sandbox directories) into a fresh sandbox.
     *
     * @param workspaceRoot workspace root
     * @param sandboxRoot sandbox root directory
     * @param runId run id
     * @return the sandbox
     * @throws IOException on I/O errors
     */
    public static WorkspaceSandbox create(Path workspaceRoot, Path sandboxRoot, String runId) throws IOException {
        Path source = workspaceRoot.toAbsolutePath().normalize();
        Path target = pathFor(sandboxRoot, runId);
        if (target.startsWith(source) && !sandboxRoot.toAbsolutePath().normalize().getFileName().toString()
                .equals("sandbox")) {
            throw new IOException("sandbox root must be outside the workspace or be named 'sandbox'");
        }
        if (Files.exists(target)) {
            deleteRecursively(target);
        }
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && (CodebaseIndexer.SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        || dir.startsWith(target))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new WorkspaceSandbox(target);
    }

    /**
     * Opens an existing sandbox.
     *
     * @param sandboxRoot sandbox root directory
     * @param runId run id
     * @return the sandbox
     * @throws IOException when it does not exist
     */
    public static WorkspaceSandbox open(Path sandboxRoot, String runId) throws IOException {
        Path target = pathFor(sandboxRoot, runId);
        if (!Files.isDirectory(target)) {
            throw new IOException("sandbox does not exist: " + target);
        }
        return new WorkspaceSandbox(target);
    }

    /**
     * Returns the sandbox directory.
     *
     * @return the path
     */
    public Path path() {
        return path;
    }

    /**
     * Applies a change set inside the sandbox: CREATE and FULL MODIFY contents are written, DELETE removes the
     * file. Partial content of a MODIFY (SKELETON or DIFF) never overwrites the existing file; it is written
     * next to it as {@code <file>.skeleton} or {@code <file>.diff} for human completion.
     *
     * @param changeSet the change set
     * @return the written or deleted paths (relative)
     * @throws IOException on I/O errors
     * @throws IllegalArgumentException when a path escapes the sandbox
     */
    public List<String> apply(ChangeSet changeSet) throws IOException {
        List<String> applied = new ArrayList<>();
        for (ChangeSet.FileChange change : changeSet.changes()) {
            String relative = change.path();
            if (change.operation() == ChangeSet.Operation.MODIFY
                    && change.contentKind() == ChangeSet.ContentKind.SKELETON) {
                relative = relative + ".skeleton";
            } else if (change.contentKind() == ChangeSet.ContentKind.DIFF) {
                relative = relative + ".diff";
            }
            Path file = resolveInside(relative);
            switch (change.operation()) {
                case CREATE, MODIFY -> {
                    if (change.contentKind() == ChangeSet.ContentKind.NONE) {
                        continue;
                    }
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, change.content(), StandardCharsets.UTF_8);
                }
                case DELETE -> Files.deleteIfExists(file);
            }
            applied.add(relative);
        }
        return applied;
    }

    /**
     * Resolves a relative path inside the sandbox.
     *
     * @param relative relative path with forward slashes
     * @return the absolute path inside the sandbox
     * @throws IllegalArgumentException when the path escapes the sandbox
     */
    public Path resolveInside(String relative) {
        Path resolved = path.resolve(relative.replace('\\', '/')).normalize();
        if (!resolved.startsWith(path) || resolved.equals(path)) {
            throw new IllegalArgumentException("path escapes the sandbox: " + relative);
        }
        return resolved;
    }

    /**
     * Deletes a sandbox directory, but only when it lies inside {@code sandboxRoot}.
     *
     * @param sandboxRoot sandbox root directory
     * @param target directory to delete
     * @throws IOException on I/O errors
     * @throws IllegalArgumentException when the target is not inside the sandbox root
     */
    public static void delete(Path sandboxRoot, Path target) throws IOException {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path dir = target.toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new IllegalArgumentException("refusing to delete a directory outside the sandbox root: " + dir);
        }
        if (Files.exists(dir)) {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
