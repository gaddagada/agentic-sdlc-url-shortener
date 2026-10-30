package com.agenticsdlc.orchestrator.core.graph;

import java.util.Objects;

/**
 * Entry and exit gate behaviour of a stage.
 *
 * @param entryApproval approval requirement before the stage starts
 * @param exitApproval approval requirement before the stage's artifact is accepted as final
 */
public record GateSpec(ApprovalRequirement entryApproval, ApprovalRequirement exitApproval) {

    /**
     * Validates the gate specification.
     */
    public GateSpec {
        Objects.requireNonNull(entryApproval, "entryApproval");
        Objects.requireNonNull(exitApproval, "exitApproval");
    }

    /**
     * Returns a gate without approvals.
     *
     * @return NEVER/NEVER
     */
    public static GateSpec none() {
        return new GateSpec(ApprovalRequirement.NEVER, ApprovalRequirement.NEVER);
    }

    /**
     * Returns a gate with only an exit approval requirement.
     *
     * @param exit exit approval requirement
     * @return NEVER/exit
     */
    public static GateSpec exit(ApprovalRequirement exit) {
        return new GateSpec(ApprovalRequirement.NEVER, exit);
    }
}
