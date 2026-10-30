package com.agenticsdlc.orchestrator.core.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.IndexedType;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Prompt templates of the LLM agents. A prompt has three parts, from most to least stable, so that providers can
 * cache the prefix ({@link LlmRequest}):
 *
 * <ol>
 *   <li>{@link #system(AgentRole)}: the role, the governance constraints, output size and role guidance;</li>
 *   <li>{@link #stableContext(StageContext, JsonCodec)}: content identical for every stage of a run, namely the
 *       active requirement, the clarification answers, the accepted {@link RequirementSpec} (full JSON) and, for the
 *       codebase-aware roles (analysis, architecture, implementation), the codebase index;</li>
 *   <li>{@link #user(StageContext, JsonCodec, Map)}: stage-specific content, namely the artifacts of the stage's
 *       direct predecessors (full JSON), summaries of the other upstream artifacts, the source of impacted files
 *       (implementation, verification, security review of REPO runs), feedback, the previous attempt's error and
 *       the output schema.</li>
 * </ol>
 *
 * <p>Context policy (bounds prompt size and token spend): the full JSON is embedded only for the artifacts of the
 * stage's direct predecessors ({@link StageContext#directPredecessors()}) plus the accepted requirement
 * specification, which every stage needs verbatim. Every other transitive upstream artifact is included as a
 * compact summary ({@link com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload#contextSummary(int)}, about
 * {@value #SUMMARY_CHARS} characters; the change set summary lists files without their content). Their decisions
 * are already reflected in the direct predecessors' artifacts.</p>
 */
public final class PromptTemplates {

    /** Governance constraints included in every system prompt. */
    public static final String GOVERNANCE = "You operate inside a governed SDLC pipeline. Only reference files that "
            + "appear in the provided codebase index. Never include secrets. Respond only with the requested JSON.";

    /** Output format rule for enum fields, included in every system prompt. */
    public static final String ENUM_VALUES = "Every enum field must use exactly one of the upper-case values listed "
            + "in its schema \"enum\" array (for example PERFORMANCE, not performance).";

    /** Output size rule included in every system prompt. */
    public static final String OUTPUT_SIZE = "Output size is limited: be specific and brief, do not restate the "
            + "upstream artifacts, and include only what the schema asks for.";

    /** Heading of the requirement section (stable context). */
    public static final String REQUIREMENT_HEADING = "## Requirement";

    /** Heading of the accepted requirement specification (stable context). */
    public static final String REQUIREMENT_SPEC_HEADING = "## Accepted requirement specification (full JSON)";

    /** Heading of the full-JSON artifact section of the user prompt. */
    public static final String FULL_ARTIFACTS_HEADING = "## Upstream artifacts (full JSON)";

    /** Heading of the summarized artifact section of the user prompt. */
    public static final String SUMMARIZED_ARTIFACTS_HEADING = "## Earlier upstream artifacts (summaries)";

    /** Heading of the impacted source section of the user prompt. */
    public static final String SOURCES_HEADING = "## Source of impacted files (authoritative; write diffs against "
            + "exactly this text)";

    /** Rules for DIFF content, part of the implementation guidance. */
    public static final String DIFF_RULES = "When the prompt shows the source of a file under \"Source of impacted "
            + "files\", write DIFF hunks only against exactly that text: start the diff with \"--- a/<path>\" and "
            + "\"+++ b/<path>\", give every hunk a correct \"@@ -a,b +c,d @@\" header (a = first line of the hunk in "
            + "the shown source, b = its number of context and removed lines, c and d the same for the result), "
            + "include three unchanged context lines before and after every change, copied verbatim with their "
            + "indentation, and prefix every hunk line with a space (context), \"-\" (removed) or \"+\" (added). "
            + "Use contentKind FULL (operation CREATE) for new files. If a diff does not apply, the error names the "
            + "hunk and the mismatching line and the next attempt shows that file's source.";

    /** Rule that tells the verifier how to tell blocking from advisory findings. */
    public static final String BLOCKING_RULES = "Mark every structural check blocking=true when its failure would "
            + "break the build, violate the design or a policy, or leave a MUST requirement without implementation "
            + "or tests; mark it blocking=false (advisory) for style, test placement, performance tuning and optional "
            + "improvements. Set passed to true exactly when no blocking check failed and no requirement is "
            + "uncovered: advisory failures never fail verification.";

    /** Roles whose user prompt shows the source of impacted files (when the context carries it). */
    public static final Set<AgentRole> SOURCE_ROLES = Collections.unmodifiableSet(EnumSet.of(AgentRole.IMPLEMENTATION,
            AgentRole.VERIFICATION, AgentRole.SECURITY_REVIEW));

    /** Maximum characters of one full upstream artifact in a prompt. */
    static final int MAX_ARTIFACT_CHARS = 60_000;

    /** Approximate characters of one summarized upstream artifact in a prompt. */
    public static final int SUMMARY_CHARS = 1_500;

    /** Maximum indexed types listed in a prompt. */
    static final int MAX_INDEX_TYPES = 400;

    private PromptTemplates() {
    }

    /**
     * Returns the system prompt of a role.
     *
     * @param role the role
     * @return the system prompt
     */
    public static String system(AgentRole role) {
        return "You are the " + role.description() + ".\n" + GOVERNANCE + "\n" + ENUM_VALUES + "\n" + OUTPUT_SIZE
                + "\n" + guidance(role);
    }

    private static String guidance(AgentRole role) {
        String roots = String.join(" or ", WorkspaceSandbox.ALLOWED_ROOTS);
        return switch (role) {
            case REQUIREMENTS -> "Score ambiguity between 0 and 1 and ask clarification questions (ids q1, q2, ...) "
                    + "for every ambiguity finding. Use ids FR-n, NFR-n and AC-n; every acceptance criterion traces "
                    + "existing requirement ids.";
            case PLANNING -> "Task ids are T1, T2, ...; the dependency graph must be acyclic; human-owned tasks are "
                    + "reviews and approvals.";
            case CODEBASE_ANALYSIS -> "Use only types, paths and endpoints from the codebase index. Confidence is "
                    + "between 0 and 1.";
            case ARCHITECTURE -> "Consider at least two alternatives and choose exactly one. Mark breaking API changes "
                    + "and backward-incompatible data changes explicitly. Keep the design compact: list only the "
                    + "components that change, one sentence per responsibility, no code.";
            case IMPLEMENTATION -> "Paths are repository-relative with forward slashes and start with " + roots
                    + " (the orchestrator modules, the build wrapper and CI files are off limits). Keep the change "
                    + "set compact; the response is cut off at the output token limit. Change only the files the "
                    + "design needs. For an existing file (one listed in the codebase index) use operation MODIFY with "
                    + "contentKind DIFF: a unified diff of only the changed hunks; never repeat unchanged code. "
                    + DIFF_RULES + " Use operation CREATE with contentKind FULL only for new files that do not exist "
                    + "yet; never CREATE an existing file. Add tests with real assertions for every production change "
                    + "and a new Flyway migration for every data model change. buildCommand is informational: the "
                    + "engine always runs its own fixed build. When verification feedback is present, return the "
                    + "complete corrected change set (same compact rules, every feedback item addressed) rather than "
                    + "a partial delta, and keep it well under the output limit: prefer fewer, essential files over "
                    + "exhaustive boilerplate.";
            case TEST_DESIGN -> "Test case ids are TC-n; every requirement id appears in the coverage matrix or in "
                    + "risksNotCovered. coverageMatrix values must be comma-separated test case ids only, for "
                    + "example \"TC-1, TC-4\": no prose, notes or parentheses (explanations belong in the test "
                    + "cases or in risksNotCovered). Keep steps short.";
            case VERIFICATION -> BLOCKING_RULES + " Give imperative feedback for each failed blocking check; prefix "
                    + "feedback for an advisory finding with \"Advisory:\". When the prompt shows the source of "
                    + "impacted files, compare every DIFF hunk with it: a hunk whose context or removed lines do not "
                    + "match the shown source cannot be applied, which is a blocking failure. Set skeletonOnly to "
                    + "true when any change has contentKind SKELETON. You do not run builds: add the note \""
                    + VerificationReport.NO_BUILD_NOTE + "\" to notes.";
            case DOCUMENTATION -> "Write concise GitHub-flavoured Markdown. Document only what this change adds or "
                    + "alters; do not repeat unchanged documentation.";
            case SECURITY_REVIEW -> "Use STRIDE categories; passed is true only when there is no HIGH or CRITICAL "
                    + "finding. When the prompt shows the source of impacted files, judge the change against it.";
            case RELEASE -> "Leave changeRecord.approvals empty and changeRecord.signature null: approvals are recorded "
                    + "and the record is signed by the engine from human "
                    + "decisions only. A checklist item with status FAIL requires the decision NO_GO; never mark "
                    + "an item WAIVED (only humans waive). When the change set contains SKELETON content, mark the "
                    + "item \"Code complete (skeleton plan)\" N_A with evidence and add the condition "
                    + "\"implementation is a skeleton change plan; complete and build before merge\".";
        };
    }

    /**
     * Returns the run-stable part of the prompt: the active requirement, the clarification answers, the accepted
     * requirement specification (full JSON) and, for codebase-aware roles, the codebase index. Identical for every
     * attempt of a role within a plan version, so a provider can cache it.
     *
     * @param context the stage context
     * @param codec codec used to render the requirement specification
     * @return the stable context (never empty: it always contains the requirement)
     */
    public static String stableContext(StageContext context, JsonCodec codec) {
        StringBuilder out = new StringBuilder();
        out.append(REQUIREMENT_HEADING).append('\n').append(context.requirement()).append("\n\n");
        if (!context.clarificationAnswers().isEmpty()) {
            out.append("## Clarification answers (authoritative)\n");
            for (ClarificationQuestion q : context.clarificationQuestions()) {
                String answer = context.clarificationAnswers().get(q.id());
                if (answer != null) {
                    out.append("- ").append(q.id()).append(" ").append(q.question()).append("\n  Answer: ")
                            .append(answer).append('\n');
                }
            }
            out.append('\n');
        }
        List<Map.Entry<String, Artifact>> specs = context.upstream().entrySet().stream()
                .filter(e -> e.getValue().payload() instanceof RequirementSpec).toList();
        if (!specs.isEmpty()) {
            out.append(REQUIREMENT_SPEC_HEADING).append('\n');
            for (Map.Entry<String, Artifact> entry : specs) {
                appendFull(out, entry.getKey(), entry.getValue(), codec);
            }
            out.append('\n');
        }
        appendIndex(out, context);
        return out.toString();
    }

    /**
     * Returns the stage-specific part of the prompt: the direct predecessors' artifacts (full JSON, the requirement
     * specification excepted: it is in the stable context), summaries of the other upstream artifacts, the source of
     * impacted files, feedback, the previous attempt's error and the output schema.
     *
     * @param context the stage context
     * @param codec codec used to render upstream artifacts
     * @param schema JSON schema of the expected output
     * @return the user prompt
     */
    public static String user(StageContext context, JsonCodec codec, Map<String, Object> schema) {
        StringBuilder out = new StringBuilder();
        appendUpstream(out, context, codec);
        appendSources(out, context);
        if (!context.feedback().isEmpty()) {
            out.append("## Feedback to address\n");
            context.feedback().forEach(f -> out.append("- ").append(f).append('\n'));
            out.append('\n');
        }
        if (context.previousError() != null) {
            out.append("## The previous attempt failed\n").append(context.previousError())
                    .append("\nFix the problem in this attempt.\n\n");
        }
        out.append("## Output\nReturn a single JSON object (no prose, no code fences) that matches this JSON schema:\n")
                .append(JsonCodec.standard().toJson(schema)).append('\n');
        return out.toString();
    }

    /**
     * Returns the complete prompt text after the system prompt: {@link #stableContext} followed by {@link #user}
     * (what a client that sends one user message transmits; see {@link LlmRequest#userWithContext()}).
     *
     * @param context the stage context
     * @param codec codec
     * @param schema JSON schema of the expected output
     * @return the combined prompt
     */
    public static String combined(StageContext context, JsonCodec codec, Map<String, Object> schema) {
        return stableContext(context, codec) + "\n" + user(context, codec, schema);
    }

    /**
     * Returns whether an upstream artifact is embedded as full JSON (in the stable context for the requirement
     * specification, in the user prompt for the artifacts of direct predecessors).
     *
     * @param context the stage context
     * @param stageId upstream stage id
     * @param artifact the artifact
     * @return true for full JSON, false for a summary
     */
    static boolean includedInFull(StageContext context, String stageId, Artifact artifact) {
        return context.isDirectPredecessor(stageId) || artifact.payload() instanceof RequirementSpec;
    }

    private static void appendFull(StringBuilder out, String stageId, Artifact artifact, JsonCodec codec) {
        String json = codec.toPrettyJson(artifact.payload());
        if (json.length() > MAX_ARTIFACT_CHARS) {
            json = json.substring(0, MAX_ARTIFACT_CHARS) + "\n... (truncated)";
        }
        out.append("### ").append(stageId).append(" (").append(artifact.kind()).append(" v")
                .append(artifact.version()).append(")\n```json\n").append(json).append("\n```\n");
    }

    private static void appendUpstream(StringBuilder out, StageContext context, JsonCodec codec) {
        List<Map.Entry<String, Artifact>> full = new ArrayList<>();
        List<Map.Entry<String, Artifact>> summarized = new ArrayList<>();
        for (Map.Entry<String, Artifact> entry : context.upstream().entrySet()) {
            if (entry.getValue().payload() instanceof RequirementSpec) {
                continue; // part of the stable context
            }
            (includedInFull(context, entry.getKey(), entry.getValue()) ? full : summarized).add(entry);
        }
        if (!full.isEmpty()) {
            out.append(FULL_ARTIFACTS_HEADING).append('\n')
                    .append("The artifacts of this stage's direct inputs (the requirement specification is above).\n");
            for (Map.Entry<String, Artifact> entry : full) {
                appendFull(out, entry.getKey(), entry.getValue(), codec);
            }
            out.append('\n');
        }
        if (!summarized.isEmpty()) {
            out.append(SUMMARIZED_ARTIFACTS_HEADING).append('\n')
                    .append("Indirect inputs, summarized; their decisions are already reflected in the artifacts "
                            + "above.\n");
            for (Map.Entry<String, Artifact> entry : summarized) {
                Artifact artifact = entry.getValue();
                String summary = artifact.payload().contextSummary(SUMMARY_CHARS).strip();
                String fence = summary.contains("```") ? "~~~~" : "```";
                out.append("### ").append(entry.getKey()).append(" (").append(artifact.kind()).append(" v")
                        .append(artifact.version()).append(", summary)\n").append(fence).append("markdown\n")
                        .append(summary).append('\n').append(fence).append('\n');
            }
            out.append('\n');
        }
    }

    private static void appendSources(StringBuilder out, StageContext context) {
        if (!context.hasImpactedSources() || !SOURCE_ROLES.contains(context.role())) {
            return;
        }
        out.append(SOURCES_HEADING).append('\n');
        out.append(switch (context.role()) {
            case IMPLEMENTATION -> "The current content of the files this change modifies, extends or may affect, "
                    + "read from the workspace. Write every DIFF hunk against exactly these lines (headers count "
                    + "lines of this text); use FULL content for new files.\n";
            case VERIFICATION -> "The current content of the impacted files, read from the workspace. Compare every "
                    + "DIFF hunk of the change set with it: context and removed lines must match this text.\n";
            default -> "The current content of the impacted files, read from the workspace.\n";
        });
        for (Map.Entry<String, String> source : context.impactedSources().entrySet()) {
            String content = source.getValue();
            String fence = fence(content);
            long lines = content.isEmpty() ? 0 : content.lines().count();
            out.append("### ").append(source.getKey()).append(" (").append(lines).append(" lines)\n").append(fence)
                    .append(language(source.getKey())).append('\n').append(content);
            if (!content.endsWith("\n")) {
                out.append('\n');
            }
            out.append(fence).append('\n');
        }
        if (!context.impactedSourceNotes().isEmpty()) {
            out.append("Not shown in full:\n");
            context.impactedSourceNotes().forEach(n -> out.append("- ").append(n).append('\n'));
        }
        out.append('\n');
    }

    /** Returns a code fence that does not occur in the content. */
    private static String fence(String content) {
        String fence = "```";
        while (content.contains(fence)) {
            fence += "`";
        }
        return fence;
    }

    private static String language(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "java" -> "java";
            case "kt" -> "kotlin";
            case "sql" -> "sql";
            case "yml", "yaml" -> "yaml";
            case "xml" -> "xml";
            case "md" -> "markdown";
            case "json" -> "json";
            case "properties" -> "properties";
            case "html" -> "html";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            default -> "";
        };
    }

    private static void appendIndex(StringBuilder out, StageContext context) {
        CodebaseIndex index = context.workspaceIndex();
        if (index == null || index.isEmpty() || (context.role() != AgentRole.CODEBASE_ANALYSIS
                && context.role() != AgentRole.ARCHITECTURE && context.role() != AgentRole.IMPLEMENTATION)) {
            return;
        }
        out.append("## Codebase index (").append(index.rootName()).append(", ").append(index.types().size())
                .append(" types)\n");
        int listed = 0;
        for (IndexedType type : index.types()) {
            if (listed++ >= MAX_INDEX_TYPES) {
                out.append("- ... (").append(index.types().size() - MAX_INDEX_TYPES).append(" more)\n");
                break;
            }
            out.append("- ").append(type.path()).append(" [").append(type.kind()).append(']');
            if (!type.endpoints().isEmpty()) {
                out.append(" endpoints: ").append(String.join(", ", type.endpoints().stream()
                        .map(e -> e.method() + " " + e.path()).toList()));
            }
            out.append('\n');
        }
        if (!index.migrations().isEmpty()) {
            out.append("- migrations: ").append(String.join(", ", index.migrations())).append('\n');
        }
        if (!index.configFiles().isEmpty()) {
            out.append("- configuration files: ").append(String.join(", ", index.configFiles())).append('\n');
        }
        out.append('\n');
    }
}
