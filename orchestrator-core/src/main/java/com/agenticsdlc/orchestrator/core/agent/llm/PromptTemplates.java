package com.agenticsdlc.orchestrator.core.agent.llm;

import java.util.Map;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.IndexedType;

/**
 * Prompt templates of the LLM agents: a role-specific system prompt with the governance constraints, and a
 * user prompt with the active requirement, clarification answers, accepted upstream artifacts as JSON, the
 * codebase index (for codebase-aware roles), feedback, the previous attempt's error and the output schema.
 */
public final class PromptTemplates {

    /** Governance constraints included in every system prompt. */
    public static final String GOVERNANCE = "You operate inside a governed SDLC pipeline. Only reference files that "
            + "appear in the provided codebase index. Never include secrets. Respond only with the requested JSON.";

    /** Maximum characters of one upstream artifact in a prompt. */
    static final int MAX_ARTIFACT_CHARS = 60_000;

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
        return "You are the " + role.description() + ".\n" + GOVERNANCE + "\n" + guidance(role);
    }

    private static String guidance(AgentRole role) {
        return switch (role) {
            case REQUIREMENTS -> "Score ambiguity between 0 and 1 and ask clarification questions (ids q1, q2, ...) "
                    + "for every ambiguity finding. Use ids FR-n, NFR-n and AC-n; every acceptance criterion traces "
                    + "existing requirement ids.";
            case PLANNING -> "Task ids are T1, T2, ...; the dependency graph must be acyclic; human-owned tasks are "
                    + "reviews and approvals.";
            case CODEBASE_ANALYSIS -> "Use only types, paths and endpoints from the codebase index. Confidence is "
                    + "between 0 and 1.";
            case ARCHITECTURE -> "Consider at least two alternatives and choose exactly one. Mark breaking API changes "
                    + "and backward-incompatible data changes explicitly.";
            case IMPLEMENTATION -> "Use contentKind FULL and include the complete content of every CREATE and MODIFY "
                    + "file. Paths are repository-relative with forward slashes and start with url-shortener/, "
                    + "orchestrator-core/, orchestrator-app/ or docs/. Add tests for every production change and a "
                    + "Flyway migration for every data model change.";
            case TEST_DESIGN -> "Test case ids are TC-n; every requirement id appears in the coverage matrix or in "
                    + "risksNotCovered.";
            case VERIFICATION -> "Set passed to true only if every check passed and no requirement is uncovered; give "
                    + "imperative feedback for each failed check.";
            case DOCUMENTATION -> "Write concise GitHub-flavoured Markdown.";
            case SECURITY_REVIEW -> "Use STRIDE categories; passed is true only when there is no HIGH or CRITICAL "
                    + "finding.";
            case RELEASE -> "Leave changeRecord.approvals empty: approvals are recorded by the engine from human "
                    + "decisions only.";
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
        if (!context.upstream().isEmpty()) {
            out.append("## Accepted upstream artifacts\n");
            for (Map.Entry<String, Artifact> entry : context.upstream().entrySet()) {
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
        CodebaseIndex index = context.workspaceIndex();
        if (index != null && !index.isEmpty() && (context.role() == AgentRole.CODEBASE_ANALYSIS
                || context.role() == AgentRole.ARCHITECTURE || context.role() == AgentRole.IMPLEMENTATION)) {
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
            out.append('\n');
        }
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
}
