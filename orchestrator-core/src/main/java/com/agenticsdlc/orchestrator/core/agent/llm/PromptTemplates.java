package com.agenticsdlc.orchestrator.core.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * Prompt templates of the LLM agents: a role-specific system prompt with the governance constraints and output
 * size guidance, and a user prompt with the active requirement, clarification answers, upstream artifacts, the
 * codebase index (for codebase-aware roles), feedback, the previous attempt's error and the output schema.
 *
 * <p>Context policy (bounds prompt size and token spend): the user prompt embeds the full JSON only of the
 * artifacts of the stage's direct predecessors ({@link StageContext#directPredecessors()}) plus the accepted
 * {@link RequirementSpec}, which every stage needs verbatim. Every other transitive upstream artifact is included
 * as a compact summary ({@link com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload#contextSummary(int)},
 * about {@value #SUMMARY_CHARS} characters; the change set summary lists files without their content). Their
 * decisions are already reflected in the direct predecessors' artifacts.</p>
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

    /** Heading of the full-JSON artifact section of the user prompt. */
    public static final String FULL_ARTIFACTS_HEADING = "## Upstream artifacts (full JSON)";

    /** Heading of the summarized artifact section of the user prompt. */
    public static final String SUMMARIZED_ARTIFACTS_HEADING = "## Earlier upstream artifacts (summaries)";

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
                    + "contentKind DIFF: a unified diff of only the changed hunks with at most three context lines; "
                    + "never repeat unchanged code. Use operation CREATE with contentKind FULL only for new files "
                    + "that do not exist yet; never CREATE an existing file. Add tests with real assertions for every "
                    + "production change and a new Flyway migration for every data model change. buildCommand is "
                    + "informational: the engine always runs its own fixed build.";
            case TEST_DESIGN -> "Test case ids are TC-n; every requirement id appears in the coverage matrix or in "
                    + "risksNotCovered. coverageMatrix values must be comma-separated test case ids only, for "
                    + "example \"TC-1, TC-4\": no prose, notes or parentheses (explanations belong in the test "
                    + "cases or in risksNotCovered). Keep steps short.";
            case VERIFICATION -> "Set passed to true only if every check passed and no requirement is uncovered; give "
                    + "imperative feedback for each failed check. Set skeletonOnly to true when any change has "
                    + "contentKind SKELETON. You do not run builds: add the note \""
                    + VerificationReport.NO_BUILD_NOTE + "\" to notes.";
            case DOCUMENTATION -> "Write concise GitHub-flavoured Markdown. Document only what this change adds or "
                    + "alters; do not repeat unchanged documentation.";
            case SECURITY_REVIEW -> "Use STRIDE categories; passed is true only when there is no HIGH or CRITICAL "
                    + "finding.";
            case RELEASE -> "Leave changeRecord.approvals empty: approvals are recorded by the engine from human "
                    + "decisions only. A checklist item with status FAIL requires the decision NO_GO; never mark "
                    + "an item WAIVED (only humans waive). When the change set contains SKELETON content, mark the "
                    + "item \"Code complete (skeleton plan)\" N_A with evidence and add the condition "
                    + "\"implementation is a skeleton change plan; complete and build before merge\".";
        };
    }

    /**
     * Returns the user prompt of an attempt.
     *
     * @param context the stage context
     * @param codec codec used to render upstream artifacts
     * @param schema JSON schema of the expected output
     * @return the user prompt
     */
    public static String user(StageContext context, JsonCodec codec, Map<String, Object> schema) {
        StringBuilder out = new StringBuilder();
        out.append("## Requirement\n").append(context.requirement()).append("\n\n");
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
        appendUpstream(out, context, codec);
        appendIndex(out, context);
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
     * Returns whether an upstream artifact is embedded as full JSON: artifacts of direct predecessors and the
     * requirement specification.
     *
     * @param context the stage context
     * @param stageId upstream stage id
     * @param artifact the artifact
     * @return true for full JSON, false for a summary
     */
    static boolean includedInFull(StageContext context, String stageId, Artifact artifact) {
        return context.isDirectPredecessor(stageId) || artifact.payload() instanceof RequirementSpec;
    }

    private static void appendUpstream(StringBuilder out, StageContext context, JsonCodec codec) {
        List<Map.Entry<String, Artifact>> full = new ArrayList<>();
        List<Map.Entry<String, Artifact>> summarized = new ArrayList<>();
        for (Map.Entry<String, Artifact> entry : context.upstream().entrySet()) {
            (includedInFull(context, entry.getKey(), entry.getValue()) ? full : summarized).add(entry);
        }
        if (!full.isEmpty()) {
            out.append(FULL_ARTIFACTS_HEADING).append('\n')
                    .append("The requirement specification and the artifacts of this stage's direct inputs.\n");
            for (Map.Entry<String, Artifact> entry : full) {
                Artifact artifact = entry.getValue();
                String json = codec.toPrettyJson(artifact.payload());
                if (json.length() > MAX_ARTIFACT_CHARS) {
                    json = json.substring(0, MAX_ARTIFACT_CHARS) + "\n... (truncated)";
                }
                out.append("### ").append(entry.getKey()).append(" (").append(artifact.kind()).append(" v")
                        .append(artifact.version()).append(")\n```json\n").append(json).append("\n```\n");
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
