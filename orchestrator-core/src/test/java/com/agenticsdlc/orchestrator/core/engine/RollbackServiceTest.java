package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Checkpoint;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

class RollbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String IMPLEMENT = WorkflowDefinitions.IMPLEMENT;

    private final RollbackService rollback = new RollbackService(List.of(), new RunControl(),
            NoopMetricsRecorder.INSTANCE);

    private static Run runInPlanVersion2() {
        Run run = Run.create("run-1", new WorkflowGraph(WorkflowDefinitions.defaultSdlc()), "test", "Create links.",
                RunOptions.defaults(), NOW);
        run.addCheckpoint(new Checkpoint("cp-v1", IMPLEMENT, 1, Map.of("intake", 1, "design", 1), List.of(), NOW));
        run.incrementPlanVersion();
        run.accept("intake", 2);
        run.accept("design", 2);
        run.stage("intake").setStatus(StageStatus.SUCCEEDED);
        run.stage("design").setStatus(StageStatus.SUCCEEDED);
        run.stage(IMPLEMENT).setStatus(StageStatus.FAILED);
        return run;
    }

    private static AuditEvent rollbackEvent(RunMutation tx) {
        return tx.events().stream().filter(e -> e.type() == AuditEventType.ROLLBACK_COMPLETED).findFirst()
                .orElseThrow();
    }

    @Test
    void aCheckpointOfASupersededPlanIsIgnoredAndThePlanVersionNeverMovesBackwards() {
        Run run = runInPlanVersion2();
        RunMutation tx = new RunMutation(new RunRegistry.RunHolder(run), NOW);

        rollback.rollback(tx, IMPLEMENT, "entry gate denied", HaltReason.POLICY_DENIED);

        assertThat(run.planVersion()).isEqualTo(2);
        assertThat(run.acceptedVersions()).containsEntry("intake", 2).containsEntry("design", 2);
        assertThat(run.stage("design").status()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(run.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.haltReason()).isEqualTo(HaltReason.POLICY_DENIED);
        assertThat(rollbackEvent(tx).data()).doesNotContainKey("checkpointId")
                .containsEntry("ignoredCheckpointId", "cp-v1");
    }

    @Test
    void theLatestCheckpointOfTheCurrentPlanIsRestored() {
        Run run = runInPlanVersion2();
        run.addCheckpoint(new Checkpoint("cp-v2", IMPLEMENT, 2, Map.of("intake", 2), List.of(), NOW));
        RunMutation tx = new RunMutation(new RunRegistry.RunHolder(run), NOW);

        rollback.rollback(tx, IMPLEMENT, "agent failed", HaltReason.AGENT_FAILURE_EXHAUSTED);

        assertThat(run.planVersion()).isEqualTo(2);
        assertThat(run.acceptedVersions()).containsOnlyKeys("intake").containsEntry("intake", 2);
        assertThat(run.stage("design").status()).as("its accepted design was rolled back")
                .isEqualTo(StageStatus.PENDING);
        assertThat(rollbackEvent(tx).data()).containsEntry("checkpointId", "cp-v2")
                .doesNotContainKey("ignoredCheckpointId");
    }
}
