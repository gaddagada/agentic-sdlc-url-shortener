package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code implement} stage: the proposed file changes.
 *
 * @param baseRevision the revision the change applies to
 * @param changes file changes
 * @param buildCommand command the agent proposes to build and test the change; informational only: sandbox
 *                     builds always run the engine-configured command ({@code EngineConfig.buildCommand})
 * @param risks implementation risks and documented justifications
 * @param rollbackPlan how to roll the change back
 * @param traces task ids implemented
 */
public record ChangeSet(String baseRevision, List<FileChange> changes, String buildCommand, List<String> risks,
                        String rollbackPlan, List<String> traces) implements ArtifactPayload {

    /**
     * Normalizes collections and text.
     */
    public ChangeSet {
        baseRevision = Immutables.text(baseRevision);
        changes = Immutables.list(changes);
        buildCommand = Immutables.text(buildCommand);
        risks = Immutables.list(risks);
        rollbackPlan = Immutables.text(rollbackPlan);
        traces = Immutables.list(traces);
    }

    /** File operation. */
    public enum Operation {
        /** New file. */
        CREATE,
        /** Changed file. */
        MODIFY,
        /** Removed file. */
        DELETE
    }

    /** What the {@code content} field contains. */
    public enum ContentKind {
        /** A compilable skeleton with TODO markers. */
        SKELETON,
        /** Full file content. */
        FULL,
        /** A unified diff. */
        DIFF,
        /** No content. */
        NONE
    }

    /**
     * A single file change.
     *
     * @param path repository-relative path with forward slashes
     * @param operation operation
     * @param language language (java, sql, yaml, markdown, ...)
     * @param summary what changes and why
     * @param contentKind kind of content
     * @param content the content
     * @param traces task or requirement ids
     */
    public record FileChange(String path, Operation operation, String language, String summary,
                             ContentKind contentKind, String content, List<String> traces) {
        /**
         * Normalizes fields.
         */
        public FileChange {
            path = Immutables.text(path);
            language = Immutables.text(language);
            summary = Immutables.text(summary);
            content = Immutables.text(content);
            traces = Immutables.list(traces);
        }

        /**
         * Returns the number of lines in the content.
         *
         * @return line count (0 for empty content)
         */
        public int contentLines() {
            return content.isEmpty() ? 0 : (int) content.lines().count();
        }

        /**
         * Returns the file name (last path segment).
         *
         * @return file name
         */
        public String fileName() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.CHANGE_SET;
    }

    /**
     * Returns the total number of content lines across all changes.
     *
     * @return total lines
     */
    public int totalContentLines() {
        return changes.stream().mapToInt(FileChange::contentLines).sum();
    }

    @Override
    public void validate() {
        Problems p = new Problems("ChangeSet");
        p.requireNonEmpty(changes, "changes");
        p.requireText(buildCommand, "buildCommand");
        p.requireText(rollbackPlan, "rollbackPlan");
        p.requireUnique(changes, FileChange::path, "changes");
        for (FileChange c : changes) {
            p.requireText(c.path(), "file change path");
            p.require(!c.path().startsWith("/") && !c.path().contains("\\") && !c.path().contains("..")
                            && !c.path().matches("^[A-Za-z]:.*"),
                    "file change path '" + c.path() + "' must be a relative path with forward slashes and no '..'");
            p.requireNonNull(c.operation(), "file change " + c.path() + " operation");
            p.requireNonNull(c.contentKind(), "file change " + c.path() + " contentKind");
        }
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = overview();
        for (FileChange c : changes) {
            if (!c.content().isBlank()) {
                md.heading(4, c.path()).code(c.language(), c.content());
            }
        }
        return md.render();
    }

    /**
     * Summarizes the change set without file contents (operations, paths, summaries, risks, rollback plan).
     *
     * @param maxChars approximate maximum length
     * @return the summary
     */
    @Override
    public String contextSummary(int maxChars) {
        return Markdown.truncate(overview().render(), maxChars);
    }

    private Markdown overview() {
        Markdown md = new Markdown().heading(2, "Change set")
                .field("Base revision", baseRevision)
                .field("Build command", "`" + buildCommand + "`")
                .field("Files", changes.size() + " (" + totalContentLines() + " content lines)");
        md.table(List.of("Operation", "Path", "Language", "Content", "Summary"),
                changes.stream().map(c -> List.of(String.valueOf(c.operation()), c.path(), c.language(),
                        String.valueOf(c.contentKind()), c.summary())).toList());
        md.heading(3, "Risks").bullets(risks);
        md.heading(3, "Rollback plan").paragraph(rollbackPlan);
        md.field("Traces", Markdown.join(traces));
        return md;
    }
}
