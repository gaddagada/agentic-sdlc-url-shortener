package com.agenticsdlc.orchestrator.core.testsupport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Builds stage contexts for invoking agents directly.
 */
public final class Contexts {

    private static final Map<AgentRole, String> STAGES = Map.of(AgentRole.REQUIREMENTS, "intake",
            AgentRole.PLANNING, "decompose", AgentRole.CODEBASE_ANALYSIS, "impact", AgentRole.ARCHITECTURE, "design",
            AgentRole.IMPLEMENTATION, "implement", AgentRole.TEST_DESIGN, "test-design", AgentRole.VERIFICATION,
            "verify", AgentRole.DOCUMENTATION, "document", AgentRole.SECURITY_REVIEW, "security-scan",
            AgentRole.RELEASE, "release-readiness");

    private Contexts() {
    }

    public static Artifact artifact(ArtifactPayload payload) {
        AgentRole role = roleOf(payload);
        String stage = STAGES.get(role);
        return new Artifact("artifact-" + stage, "run-1", stage, payload.kind(), 1, 1, "hash-" + stage, payload,
                new Provenance(Map.of(), role, "deterministic", "deterministic", null, null, 0, 0, 0, 1, 1),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    public static StageContext of(AgentRole role, String requirement, RunOptions options, CodebaseIndex index,
                                  List<Approval> approvals, ArtifactPayload... upstream) {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        for (ArtifactPayload payload : upstream) {
            Artifact artifact = artifact(payload);
            artifacts.put(artifact.stageId(), artifact);
        }
        return new StageContext("run-1", STAGES.get(role), role, requirement, requirement, Map.of(), List.of(),
                artifacts, List.of(), 1, 1, null, index, options, approvals, AgentSettings.defaults());
    }

    public static StageContext of(AgentRole role, String requirement, ArtifactPayload... upstream) {
        return of(role, requirement, RunOptions.defaults(), null, List.of(), upstream);
    }

    public static StageContext withAnswers(String requirement, List<ClarificationQuestion> questions,
                                           Map<String, String> answers) {
        return new StageContext("run-1", "intake", AgentRole.REQUIREMENTS, requirement, requirement, answers,
                questions, Map.of(), List.of(), 1, 1, null, null, RunOptions.defaults(), List.of(),
                AgentSettings.defaults());
    }

    /** Returns a copy of a context with other agent settings. */
    public static StageContext withSettings(StageContext ctx, AgentSettings settings) {
        return new StageContext(ctx.runId(), ctx.stageId(), ctx.role(), ctx.requirement(), ctx.originalRequirement(),
                ctx.clarificationAnswers(), ctx.clarificationQuestions(), ctx.upstream(), ctx.feedback(),
                ctx.attempt(), ctx.planVersion(), ctx.previousError(), ctx.workspaceIndex(), ctx.options(),
                ctx.approvals(), settings, ctx.directPredecessors(), ctx.impactedSources(),
                ctx.impactedSourceNotes());
    }

    private static AgentRole roleOf(ArtifactPayload payload) {
        for (AgentRole role : AgentRole.values()) {
            if (role.artifactKind() == payload.kind()) {
                return role;
            }
        }
        throw new IllegalArgumentException(payload.kind().name());
    }
}
