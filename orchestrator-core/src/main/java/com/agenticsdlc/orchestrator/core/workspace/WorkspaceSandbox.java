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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.util.RepoPaths;

/**
 * A disposable copy of the workspace ({@code <sandboxRoot>/<runId>}) into which a change set is
 * materialized. All writes and deletes are confined to the sandbox directory and to the workload roots
 * ({@link #ALLOWED_ROOTS}); secrets, VCS metadata and build output are never copied into it.
 */
public final class WorkspaceSandbox {

    /** Compensation action that deletes a sandbox directory. */
    public static final String DELETE_ACTION = "DELETE_SANDBOX";

    /**
     * Repository roots a change set may touch: the workload (the URL shortener and its documentation). The
     * orchestrator modules, the build wrapper and CI configuration are the control plane and are never written.
     */
    public static final List<String> ALLOWED_ROOTS = List.of("url-shortener/", "docs/");

    /** Directory names that are never copied into a sandbox (VCS metadata, build output, runtime data, sandboxes). */
    public static final Set<String> SKIPPED_DIRECTORIES = skippedDirectories();

    private final Path path;

    private WorkspaceSandbox(Path path) {
        this.path = path;
    }

    private static Set<String> skippedDirectories() {
        Set<String> names = new TreeSet<>(CodebaseIndexer.SKIPPED_DIRECTORIES);
        names.addAll(Set.of(".git", "target", "data", "sandbox"));
        return Set.copyOf(names);
    }

    /**
     * Returns whether a repository-relative path lies under one of the {@link #ALLOWED_ROOTS} (after
     * normalization; {@code ..} segments are never allowed).
     *
     * @param relative repository-relative path
     * @return true when a change set may touch it
     */
    public static boolean isAllowedPath(String relative) {
        String normalized = RepoPaths.normalize(relative);
        return !RepoPaths.hasParentSegment(normalized) && ALLOWED_ROOTS.stream().anyMatch(root ->
                normalized.startsWith(root) && normalized.length() > root.length());
    }

    /**
     * Returns whether a file must not be copied into a sandbox because it typically holds secrets
     * ({@code .env*}, {@code *.pem}, {@code *.key}, {@code *.p12}, {@code *.jks}; case-insensitive).
     *
     * @param fileName file name (last path segment)
     * @return true for secret-bearing files
     */
    public static boolean isSecretFile(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.startsWith(".env") || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".jks");
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
     * Copies the workspace into a fresh sandbox, skipping {@link #SKIPPED_DIRECTORIES} (among them {@code .git},
     * {@code target}, {@code data} and {@code sandbox}), {@code .env*} directories, secret-bearing files
     * ({@link #isSecretFile(String)}) and symbolic links.
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
                if (!dir.equals(source)) {
                    String name = dir.getFileName().toString();
                    if (SKIPPED_DIRECTORIES.contains(name) || name.toLowerCase(Locale.ROOT).startsWith(".env")
                            || dir.startsWith(target)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !isSecretFile(file.getFileName().toString())) {
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
     * Applies a change set inside the sandbox:
     *
     * <ul>
     *   <li>{@code CREATE} with FULL (or SKELETON) content creates the file; a CREATE of a file that already exists
     *       is rejected (agents must MODIFY existing files);</li>
     *   <li>{@code MODIFY} with FULL content overwrites the file;</li>
     *   <li>{@code MODIFY} (or {@code CREATE}) with DIFF content applies the unified diff to the current file
     *       ({@link UnifiedDiff}; exact, whitespace-insensitive and offset matching) and writes the result; a DIFF
     *       of a missing file is rejected unless it only adds lines ({@code --- /dev/null});</li>
     *   <li>{@code MODIFY} with SKELETON content never overwrites the file: it is written next to it as
     *       {@code <file>.skeleton} for human completion;</li>
     *   <li>{@code DELETE} deletes the file; NONE content of a CREATE or MODIFY writes nothing.</li>
     * </ul>
     *
     * <p>The whole change set is checked, and every diff is applied in memory, before anything is written: a CREATE
     * of an existing file, a path outside the {@link #ALLOWED_ROOTS} or outside the sandbox, and a diff that does
     * not apply (with the hunk, the expected line and the nearest mismatch) reject it with a
     * {@link ChangeSetRejectedException} listing every problem.</p>
     *
     * @param changeSet the change set
     * @return the written or deleted paths (relative; {@code <file>.skeleton} for skeleton modifications)
     * @throws IOException on I/O errors
     * @throws ChangeSetRejectedException when the change set violates the workspace rules or a diff does not apply
     *                                    (nothing is written)
     */
    public List<String> apply(ChangeSet changeSet) throws IOException {
        Map<String, String> patched = check(changeSet);
        List<String> applied = new ArrayList<>();
        for (ChangeSet.FileChange change : changeSet.changes()) {
            String relative = change.path();
            boolean sidecar = change.operation() == ChangeSet.Operation.MODIFY
                    && change.contentKind() == ChangeSet.ContentKind.SKELETON;
            if (sidecar) {
                relative = relative + ".skeleton";
            }
            Path file = resolveInside(relative);
            switch (change.operation()) {
                case CREATE, MODIFY -> {
                    if (change.contentKind() == ChangeSet.ContentKind.NONE) {
                        continue;
                    }
                    String content = change.contentKind() == ChangeSet.ContentKind.DIFF ? patched.get(change.path())
                            : change.content();
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                }
                case DELETE -> Files.deleteIfExists(file);
            }
            applied.add(relative);
        }
        return applied;
    }

    /**
     * Checks the change set and applies its diffs in memory.
     *
     * @return path to patched content of every DIFF change
     * @throws ChangeSetRejectedException listing every problem
     */
    private Map<String, String> check(ChangeSet changeSet) {
        List<String> problems = new ArrayList<>();
        Map<String, String> patched = new HashMap<>();
        for (ChangeSet.FileChange change : changeSet.changes()) {
            if (!isAllowedPath(change.path())) {
                problems.add(change.path() + " is outside the allowed roots (" + String.join(", ", ALLOWED_ROOTS)
                        + ")");
                continue;
            }
            Path file;
            try {
                file = resolveInside(change.path());
            } catch (IllegalArgumentException e) {
                problems.add(change.path() + " escapes the sandbox");
                continue;
            }
            if (change.operation() == ChangeSet.Operation.CREATE && Files.exists(file)) {
                problems.add("CREATE of existing file " + change.path() + " (use MODIFY, preferably with contentKind "
                        + "DIFF)");
                continue;
            }
            if (change.operation() != ChangeSet.Operation.DELETE
                    && change.contentKind() == ChangeSet.ContentKind.DIFF) {
                String problem = patch(change, file, patched);
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ChangeSetRejectedException(problems);
        }
        return patched;
    }

    /** Applies the DIFF of a change in memory; returns the problem, or null when it applied. */
    private static String patch(ChangeSet.FileChange change, Path file, Map<String, String> patched) {
        UnifiedDiff diff;
        try {
            diff = UnifiedDiff.parse(change.content());
        } catch (UnifiedDiff.PatchException e) {
            return "DIFF of " + change.path() + " cannot be parsed: " + e.getMessage();
        }
        boolean exists = Files.isRegularFile(file);
        if (!exists && change.operation() == ChangeSet.Operation.MODIFY && !diff.createsFile()) {
            return "MODIFY of missing file " + change.path() + ": a DIFF needs the existing file (use CREATE with "
                    + "contentKind FULL for a new file)";
        }
        if (exists && diff.createsFile()) {
            return "DIFF of " + change.path() + " creates the file (--- /dev/null) but it exists; write the hunks "
                    + "against the existing source";
        }
        String original;
        try {
            original = exists ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "DIFF of " + change.path() + " cannot be applied: the file is not readable UTF-8 text";
        }
        try {
            patched.put(change.path(), diff.apply(original, change.path()));
            return null;
        } catch (UnifiedDiff.PatchException e) {
            return e.getMessage();
        }
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
