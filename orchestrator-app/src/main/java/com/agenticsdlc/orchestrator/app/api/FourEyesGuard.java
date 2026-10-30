package com.agenticsdlc.orchestrator.app.api;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * The four-eyes rule of the control plane ({@code sdlc.governance.four-eyes}, off by default): the release-readiness
 * gate must be approved by someone other than the operator who started the run (the run tag
 * {@value RunLauncher#CREATED_BY_TAG}) and other than every operator who approved the design stage in this run
 * (an approval revoked by a later re-plan still counts). Operator ids are compared case-insensitively. Rejections
 * are never restricted.
 *
 * <p>The rule is enforced in the API layer from run data the engine exposes (run tags and approvals), before the
 * command reaches the engine. To bind the check to the decision, an approval without an explicit
 * {@code approvalId} is bound to the pending approval the rule was checked against.</p>
 */
@Component
public class FourEyesGuard {

    private final boolean enabled;

    /**
     * Creates the guard from the configuration.
     *
     * @param properties configuration
     */
    @Autowired
    public FourEyesGuard(OrchestratorProperties properties) {
        this.enabled = properties.governance().fourEyes();
    }

    private FourEyesGuard(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Creates a guard (tests and non-Spring callers).
     *
     * @param enabled whether the rule is enforced
     * @return the guard
     */
    public static FourEyesGuard of(boolean enabled) {
        return new FourEyesGuard(enabled);
    }

    /**
     * Returns whether the rule is enforced.
     *
     * @return true when {@code sdlc.governance.four-eyes=true}
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Checks an approval command against the rule.
     *
     * @param run the run as currently known
     * @param stageId the stage to approve
     * @param operator the approving operator
     * @param expectedApprovalId the approval id the operator reviewed, or null
     * @return the approval id the engine must bind the decision to: {@code expectedApprovalId} when given, else
     *         the pending release approval the rule was checked against (null when the rule does not apply)
     * @throws FourEyesViolationException when the operator started the run or approved its design
     */
    public @Nullable String checkApproval(Run run, String stageId, Actor operator,
                                          @Nullable String expectedApprovalId) {
        if (!enabled || !WorkflowDefinitions.RELEASE_READINESS.equals(stageId)) {
            return expectedApprovalId;
        }
        Approval pending = run.stage(stageId).pendingApproval();
        if (pending == null) {
            // nothing to approve: the engine answers with its own state conflict
            return expectedApprovalId;
        }
        String createdBy = run.options().tags().get(RunLauncher.CREATED_BY_TAG);
        if (createdBy != null && createdBy.equalsIgnoreCase(operator.id())) {
            throw new FourEyesViolationException("four-eyes rule: " + operator.id() + " started run " + run.id()
                    + " and cannot approve its release; another operator must approve " + stageId);
        }
        for (String approver : designApprovers(run)) {
            if (approver.equalsIgnoreCase(operator.id())) {
                throw new FourEyesViolationException("four-eyes rule: " + operator.id() + " approved the design of run "
                        + run.id() + " and cannot approve its release; another operator must approve " + stageId);
            }
        }
        return expectedApprovalId != null ? expectedApprovalId : pending.id();
    }

    /**
     * Returns every operator who approved a design version of the run (approved, or approved and later revoked).
     *
     * @param run the run
     * @return approver ids in approval order
     */
    static Set<String> designApprovers(Run run) {
        Set<String> approvers = new LinkedHashSet<>();
        for (Approval approval : run.approvals()) {
            boolean granted = approval.status() == ApprovalStatus.APPROVED
                    || approval.status() == ApprovalStatus.REVOKED && approval.decidedAt() != null;
            if (WorkflowDefinitions.DESIGN.equals(approval.stageId()) && granted && approval.decidedBy() != null) {
                approvers.add(approval.decidedBy());
            }
        }
        return approvers;
    }
}
