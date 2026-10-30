package com.agenticsdlc.orchestrator.app.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;

/**
 * Periodic engine maintenance, every {@code sdlc.engine.maintenance-interval} (fixed delay, default 30 s, first run
 * one interval after startup so it never overlaps {@link StartupRecovery}):
 *
 * <ul>
 *   <li>{@link OrchestrationEngine#recoverInterrupted()}: a RUNNING run that no node holds a valid lease on is not
 *       executing anywhere (its node crashed or was killed without releasing its leases); once its lease has expired
 *       it is safe-stopped with {@code INTERRUPTED_BY_RESTART} so that an operator can resume it on any node. Runs
 *       leased by a live node are left alone;</li>
 *   <li>{@link OrchestrationEngine#evictIdleRuns()}: runs that are not executing on this node and did not change for
 *       {@code sdlc.engine.registry-retention}, and terminal runs beyond {@code sdlc.engine.registry-max-terminal-runs},
 *       are dropped from memory (they stay in the database and are reloaded on demand).</li>
 * </ul>
 *
 * <p>A pass that evicted runs logs at INFO, a quiet pass at DEBUG; the engine logs the recovery count itself. A
 * failing pass is logged and the next one runs on schedule.</p>
 */
@Component
public class EngineMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(EngineMaintenance.class);

    private final OrchestrationEngine engine;

    /**
     * Creates the task.
     *
     * @param engine the engine
     */
    public EngineMaintenance(OrchestrationEngine engine) {
        this.engine = engine;
    }

    /**
     * Runs one maintenance pass (scheduled; callable directly).
     */
    @Scheduled(fixedDelayString = "${sdlc.engine.maintenance-interval:30s}",
            initialDelayString = "${sdlc.engine.maintenance-interval:30s}")
    public void runMaintenance() {
        try {
            engine.recoverInterrupted();
        } catch (RuntimeException e) {
            LOG.warn("engine maintenance: recovery of interrupted runs failed error={}", e.toString());
        }
        try {
            int evicted = engine.evictIdleRuns();
            if (evicted > 0) {
                LOG.info("engine maintenance: evicted idle runs from memory count={}", evicted);
            } else {
                LOG.debug("engine maintenance: no idle runs to evict");
            }
        } catch (RuntimeException e) {
            LOG.warn("engine maintenance: eviction of idle runs failed error={}", e.toString());
        }
    }
}
