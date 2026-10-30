package com.agenticsdlc.orchestrator.core.engine;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * Low-level run control shared by the engine services (always called under the run lock): cancelling
 * in-flight attempts, resetting stages, revoking approvals and safe-stopping.
 */
final class RunControl {

    private static final Logger LOG = LoggerFactory.getLogger(RunControl.class);

    /**
     * Resets a stage so that it can be scheduled again: STALE when it still has an accepted artifact,
     * otherwise PENDING. A pending approval is revoked.
     *
     * @return the new status
     */
    StageStatus resetToReady(RunMutation tx, StageExecution exec, String reason, Actor actor) {
        Approval pending = exec.pendingApproval();
        if (pending != null && pending.status() == ApprovalStatus.PENDING) {
            revoke(tx, pending, reason, actor);
        }
        StageStatus target = tx.run.acceptedVersions().containsKey(exec.stageId()) ? StageStatus.STALE
                : StageStatus.PENDING;
        exec.reset(target);
        return target;
    }

    /**
     * Cancels the in-flight attempt of a stage, if any (cooperatively, by interrupting it).
     */
    void cancelInFlight(RunMutation tx, String stageId) {
        RunRegistry.InFlight inFlight = tx.holder.inFlight.remove(stageId);
        if (inFlight != null) {
            inFlight.future().cancel(true);
        }
    }

    /**
     * Cancels every in-flight attempt and resets every RUNNING stage.
     *
     * @return the reset stage ids
     */
    List<String> cancelAllInFlight(RunMutation tx, String reason, Actor actor) {
        List<String> reset = new ArrayList<>();
        for (StageExecution exec : tx.run.stages()) {
            if (exec.status() == StageStatus.RUNNING) {
                cancelInFlight(tx, exec.stageId());
                StageStatus to = resetToReady(tx, tx.run.stage(exec.stageId()), reason, actor);
                tx.audit(AuditEventType.STAGE_INVALIDATED, exec.stageId(), actor,
                        "in-flight attempt cancelled: " + reason,
                        RunMutation.data("from", StageStatus.RUNNING, "to", to, "reason", reason));
                reset.add(exec.stageId());
            }
        }
        for (RunRegistry.InFlight inFlight : tx.holder.inFlight.values()) {
            inFlight.future().cancel(true);
        }
        tx.holder.inFlight.clear();
        return reset;
    }

    /**
     * Revokes the PENDING and APPROVED approvals of a stage.
     *
     * @return the number of revoked approvals
     */
    int revokeApprovals(RunMutation tx, String stageId, String reason, Actor actor) {
        int revoked = 0;
        for (Approval approval : List.copyOf(tx.run.approvals())) {
            if (approval.stageId().equals(stageId) && (approval.status() == ApprovalStatus.PENDING
                    || approval.status() == ApprovalStatus.APPROVED)) {
                revoke(tx, approval, reason, actor);
                revoked++;
            }
        }
        return revoked;
    }

    /**
     * Revokes one approval (counter and APPROVAL_REVOKED audit).
     */
    void revoke(RunMutation tx, Approval approval, String reason, Actor actor) {
        tx.run.replaceApproval(approval.revoke(reason, tx.now));
        tx.run.updateCounters(RunCounters::plusApprovalRevoked);
        tx.audit(AuditEventType.APPROVAL_REVOKED, approval.stageId(), actor, "approval revoked: " + reason,
                RunMutation.data("approvalId", approval.id(), "kind", approval.kind(), "previousStatus",
                        approval.status(), "reason", reason));
    }

    /**
     * Safe-stops the run: cancels in-flight attempts (RUNNING stages are reset), sets SAFE_STOPPED with the
     * halt reason and audits SAFE_STOP. Pending approvals and scheduled retries are kept for resume.
     */
    void safeStop(RunMutation tx, HaltReason reason, String detail, Actor actor) {
        Run run = tx.run;
        List<String> reset = cancelAllInFlight(tx, "run safe-stopped (" + reason + ")", actor);
        run.setStatus(RunStatus.SAFE_STOPPED);
        run.setHalt(reason, detail);
        tx.audit(AuditEventType.SAFE_STOP, null, actor, "run safe-stopped: " + reason + " - " + detail,
                RunMutation.data("haltReason", reason, "detail", detail, "cancelledStages", reset));
        LOG.warn("run safe-stopped runId={} haltReason={} detail={}", run.id(), reason, detail);
    }
}
