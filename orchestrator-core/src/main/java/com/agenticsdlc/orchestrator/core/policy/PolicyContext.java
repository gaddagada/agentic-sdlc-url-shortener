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
 * @param run a private copy of the run ({@link Run#copy()}, taken by the constructor) for this evaluation;
 *            rules read it, and mutating it has no effect on the live run
 * @param candidatePayload the artifact payload under evaluation at {@link GatePhase#EXIT}, else null
 * @param autonomy autonomy level of the run
 * @param requirement the requirement under evaluation (the active requirement, or the new requirement of a
 *                    re-plan)
 */
public record PolicyContext(GatePhase phase, @Nullable String stageId, @Nullable AgentRole role, Run run,
                            @Nullable Object candidatePayload, AutonomyLevel autonomy, String requirement) {

    /**
     * Validates the context and replaces {@code run} with a private copy, so a rule can never mutate the live
     * run aggregate.
     */
    public PolicyContext {
        Objects.requireNonNull(phase, "phase");
        run = Objects.requireNonNull(run, "run").copy();
        Objects.requireNonNull(autonomy, "autonomy");
        Objects.requireNonNull(requirement, "requirement");
    }

    /**
     * Creates a run-start context on a copy of the run.
     *
     * @param run the run (copied)
     * @param requirement the requirement to evaluate
     * @return the context
     */
    public static PolicyContext runStart(Run run, String requirement) {
        return new PolicyContext(GatePhase.RUN_START, null, null, run, null, run.options().autonomy(), requirement);
    }

    /**
     * Creates an entry-gate context on a copy of the run.
     *
     * @param run the run (copied)
     * @param stageId stage id
     * @param role stage role
     * @return the context
     */
    public static PolicyContext entry(Run run, String stageId, AgentRole role) {
        return new PolicyContext(GatePhase.ENTRY, stageId, role, run, null, run.options().autonomy(),
                run.activeRequirement());
    }

    /**
     * Creates an exit-gate context on a copy of the run.
     *
     * @param run the run (copied); the engine passes a prospective view in which the candidate artifact is
     *            already accepted, so that rules see the risk the run would have once it is accepted
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
