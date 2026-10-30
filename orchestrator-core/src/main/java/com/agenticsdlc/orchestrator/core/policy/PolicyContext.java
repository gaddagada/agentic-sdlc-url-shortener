package com.agenticsdlc.orchestrator.core.policy;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * Input of a policy evaluation.
 *
 * @param phase evaluation phase
 * @param stageId stage id, null at {@link GatePhase#RUN_START}
 * @param role stage role, null at {@link GatePhase#RUN_START}
 * @param runSnapshot read-only view of the run (rules must not mutate it)
 * @param candidatePayload the artifact payload under evaluation at {@link GatePhase#EXIT}, else null
 * @param autonomy autonomy level of the run
 * @param requirement the requirement under evaluation (the active requirement, or the new requirement of a
 *                    re-plan)
 */
public record PolicyContext(GatePhase phase, @Nullable String stageId, @Nullable AgentRole role, Run runSnapshot,
                            @Nullable Object candidatePayload, AutonomyLevel autonomy, String requirement) {

    /**
     * Validates the context.
     */
    public PolicyContext {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(runSnapshot, "runSnapshot");
        Objects.requireNonNull(autonomy, "autonomy");
        Objects.requireNonNull(requirement, "requirement");
    }

    /**
     * Creates a run-start context.
     *
     * @param run the run
     * @param requirement the requirement to evaluate
     * @return the context
     */
    public static PolicyContext runStart(Run run, String requirement) {
        return new PolicyContext(GatePhase.RUN_START, null, null, run, null, run.options().autonomy(), requirement);
    }

    /**
     * Creates an entry-gate context.
     *
     * @param run the run
     * @param stageId stage id
     * @param role stage role
     * @return the context
     */
    public static PolicyContext entry(Run run, String stageId, AgentRole role) {
        return new PolicyContext(GatePhase.ENTRY, stageId, role, run, null, run.options().autonomy(),
                run.activeRequirement());
    }

    /**
     * Creates an exit-gate context.
     *
     * @param run the run
     * @param stageId stage id
     * @param role stage role
     * @param payload candidate payload
     * @return the context
     */
    public static PolicyContext exit(Run run, String stageId, AgentRole role, ArtifactPayload payload) {
        return new PolicyContext(GatePhase.EXIT, stageId, role, run, payload, run.options().autonomy(),
                run.activeRequirement());
    }

    /**
     * Returns the candidate payload if it has the given type.
     *
     * @param type payload type
     * @param <T> payload type
     * @return the payload or null
     */
    public <T> @Nullable T candidate(Class<T> type) {
        return phase == GatePhase.EXIT && type.isInstance(candidatePayload) ? type.cast(candidatePayload) : null;
    }
}
