package com.agenticsdlc.orchestrator.core.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Checkpoint;
import com.agenticsdlc.orchestrator.core.model.Compensation;
import com.agenticsdlc.orchestrator.core.model.CompensationStatus;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunCounters;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

/**
 * Saga-style rollback: executes compensations registered after the failed stage's checkpoint in reverse
 * order, restores accepted artifact versions and the plan version from the checkpoint, resets stages whose
 * accepted artifact was rolled back, and always ends in a safe-stop.
 */
final class RollbackService {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackService.class);

    private final Map<String, CompensationHandler> handlers;
    private final RunControl control;
    private final MetricsRecorder metrics;

    RollbackService(List<CompensationHandler> handlers, RunControl control, MetricsRecorder metrics) {
        Map<String, CompensationHandler> byAction = new HashMap<>();
        handlers.forEach(h -> byAction.put(h.action(), h));
        this.handlers = Map.copyOf(byAction);
        this.control = control;
        this.metrics = metrics;
    }

    /**
     * Rolls back to the failed stage's latest checkpoint and safe-stops the run. The stage must already be
     * FAILED. When a compensation fails the halt reason becomes ROLLBACK_FAILED.
     *
     * @param tx the mutation
     * @param stageId failed stage
     * @param reason failure reason
     * @param haltReason halt reason to use when every compensation succeeds
     */
    void rollback(RunMutation tx, String stageId, String reason, HaltReason haltReason) {
        Run run = tx.run;
        Actor system = Actor.system();
        Optional<Checkpoint> checkpoint = run.latestCheckpointFor(stageId);
        Set<String> before = checkpoint.map(c -> Set.copyOf(c.compensationIds())).orElse(Set.of());
        List<Compensation> toRun = new ArrayList<>();
        for (Compensation c : run.compensations()) {
            if (c.status() == CompensationStatus.REGISTERED && !before.contains(c.id())) {
                toRun.add(c);
            }
        }
        boolean compensationsOk = compensate(tx, toRun.reversed(), "rollback of " + stageId);

        Map<String, Integer> previous = new LinkedHashMap<>(run.acceptedVersions());
        List<String> resetStages = new ArrayList<>();
        if (checkpoint.isPresent()) {
            run.replaceAcceptedVersions(checkpoint.get().acceptedVersions());
            run.setPlanVersion(checkpoint.get().planVersion());
            for (StageExecution exec : run.stages()) {
                String id = exec.stageId();
                if (id.equals(stageId)) {
                    continue;
                }
                Integer was = previous.get(id);
                Integer now = run.acceptedVersions().get(id);
                if (!Objects.equals(was, now) && exec.status() != StageStatus.RUNNING) {
                    StageStatus from = exec.status();
                    control.revokeApprovals(tx, id, "rolled back to checkpoint of " + stageId, system);
                    control.resetToReady(tx, exec, "rolled back to checkpoint of " + stageId, system);
                    tx.audit(AuditEventType.STAGE_INVALIDATED, id, system,
                            "accepted artifact rolled back to the checkpoint of " + stageId,
                            RunMutation.data("from", from, "to", exec.status(), "restoredVersion", now));
                    resetStages.add(id);
                }
            }
        }
        run.updateCounters(RunCounters::plusRollback);
        safeMetric(() -> metrics.rollback(run.id()));
        tx.audit(AuditEventType.ROLLBACK_COMPLETED, stageId, system,
                "rolled back to the checkpoint of " + stageId + "; " + toRun.size() + " compensations executed"
                        + (compensationsOk ? "" : " (with failures)"),
                RunMutation.data("checkpointId", checkpoint.map(Checkpoint::id).orElse(null),
                        "compensations", toRun.stream().map(Compensation::id).toList(),
                        "restoredAcceptedVersions", run.acceptedVersions(), "resetStages", resetStages,
                        "compensationsSucceeded", compensationsOk));
        if (compensationsOk) {
            control.safeStop(tx, haltReason, reason, system);
        } else {
            control.safeStop(tx, HaltReason.ROLLBACK_FAILED, "rollback after " + haltReason + " (" + reason
                    + ") had failing compensations", system);
        }
    }

    /**
     * Executes compensations in the given order; each one is isolated, failures are audited.
     *
     * @return true when all succeeded
     */
    boolean compensate(RunMutation tx, List<Compensation> compensations, String context) {
        boolean ok = true;
        for (Compensation c : compensations) {
            CompensationHandler handler = handlers.get(c.action());
            try {
                if (handler == null) {
                    throw new IllegalStateException("no compensation handler for action " + c.action());
                }
                handler.compensate(c);
                tx.run.replaceCompensation(c.executed(tx.now));
                tx.audit(AuditEventType.COMPENSATION_EXECUTED, c.stageId(), Actor.system(),
                        "compensation executed: " + c.action() + " " + c.target(),
                        RunMutation.data("compensationId", c.id(), "action", c.action(), "target", c.target(),
                                "context", context));
            } catch (Exception e) {
                ok = false;
                String message = e.getClass().getSimpleName() + ": " + e.getMessage();
                tx.run.replaceCompensation(c.failed(tx.now, message));
                tx.audit(AuditEventType.COMPENSATION_FAILED, c.stageId(), Actor.system(),
                        "compensation failed: " + c.action() + " " + c.target() + " - " + message,
                        RunMutation.data("compensationId", c.id(), "action", c.action(), "target", c.target(),
                                "error", message, "context", context));
                LOG.error("compensation failed runId={} compensationId={} action={} error={}", tx.run.id(), c.id(),
                        c.action(), message);
                LOG.debug("compensation failure details compensationId={}", c.id(), e);
            }
        }
        return ok;
    }

    private static void safeMetric(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.warn("metrics recorder failed error={}", e.toString());
        }
    }
}
