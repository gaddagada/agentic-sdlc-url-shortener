package com.agenticsdlc.orchestrator.app.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import com.agenticsdlc.orchestrator.app.LogCapture;
import com.agenticsdlc.orchestrator.app.config.SchedulingConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;

import ch.qos.logback.classic.Level;

/**
 * {@link EngineMaintenance}: each pass recovers interrupted runs and evicts idle ones, logs at INFO only when it
 * evicted something, survives failures, and is scheduled with the fixed delay {@code sdlc.engine.maintenance-interval}.
 */
class EngineMaintenanceTest {

    private final OrchestrationEngine engine = mock(OrchestrationEngine.class);
    private final EngineMaintenance maintenance = new EngineMaintenance(engine);

    @Test
    void aPassRecoversThenEvictsAndLogsAtDebugWhenNothingWasEvicted() {
        when(engine.evictIdleRuns()).thenReturn(0);

        try (LogCapture log = LogCapture.of(EngineMaintenance.class, Level.DEBUG)) {
            maintenance.runMaintenance();

            assertThat(log.lines()).containsExactly("DEBUG engine maintenance: no idle runs to evict");
        }
        InOrder order = inOrder(engine);
        order.verify(engine).recoverInterrupted();
        order.verify(engine).evictIdleRuns();
    }

    @Test
    void evictionsAreLoggedAtInfo() {
        when(engine.evictIdleRuns()).thenReturn(3);

        try (LogCapture log = LogCapture.of(EngineMaintenance.class, Level.DEBUG)) {
            maintenance.runMaintenance();

            assertThat(log.lines()).containsExactly("INFO engine maintenance: evicted idle runs from memory count=3");
        }
    }

    @Test
    void aFailingRecoveryDoesNotStopTheEvictionOrThePass() {
        doThrow(new IllegalStateException("database unavailable")).when(engine).recoverInterrupted();
        when(engine.evictIdleRuns()).thenThrow(new IllegalStateException("registry busy"));

        try (LogCapture log = LogCapture.of(EngineMaintenance.class, Level.DEBUG)) {
            maintenance.runMaintenance();

            assertThat(log.lines()).hasSize(2).allSatisfy(line -> assertThat(line).startsWith("WARN"));
            assertThat(log.lines().get(0)).contains("recovery of interrupted runs failed", "database unavailable");
            assertThat(log.lines().get(1)).contains("eviction of idle runs failed", "registry busy");
        }
        verify(engine).evictIdleRuns();
    }

    @Test
    void thePassIsScheduledWithTheConfiguredFixedDelay() {
        new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class)
                .withBean(OrchestrationEngine.class, () -> engine)
                .withBean(EngineMaintenance.class)
                .withPropertyValues("sdlc.engine.maintenance-interval=45s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ScheduledTaskHolder holder = context.getBean(ScheduledTaskHolder.class);
                    assertThat(holder.getScheduledTasks()).extracting(ScheduledTask::getTask)
                            .filteredOn(FixedDelayTask.class::isInstance).singleElement()
                            .satisfies(task -> {
                                FixedDelayTask fixed = (FixedDelayTask) task;
                                assertThat(fixed.getIntervalDuration()).isEqualTo(Duration.ofSeconds(45));
                                assertThat(fixed.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(45));
                                assertThat(fixed.toString()).contains("runMaintenance");
                            });
                });
    }
}
