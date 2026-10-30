package com.agenticsdlc.orchestrator.app.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;

/**
 * Loads the persisted runs into the engine at startup and recovers runs that were interrupted by a shutdown or
 * crash: {@link OrchestrationEngine#recoverInterrupted()} registers every persisted run (so the runs list and the
 * metrics see them) and safe-stops RUNNING runs with {@code INTERRUPTED_BY_RESTART}; an operator decides whether
 * to resume them. Runs after the workspace index warm-up.
 */
@Component
@Order(StartupRecovery.ORDER)
public class StartupRecovery implements ApplicationRunner {

    /** Runner order (after {@link WorkspaceIndexWarmup}). */
    static final int ORDER = WorkspaceIndexWarmup.ORDER + 1;

    private static final Logger LOG = LoggerFactory.getLogger(StartupRecovery.class);

    private final OrchestrationEngine engine;

    /**
     * Creates the runner.
     *
     * @param engine the engine
     */
    public StartupRecovery(OrchestrationEngine engine) {
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.recoverInterrupted();
        LOG.info("persisted runs loaded runs={}", engine.list().size());
    }
}
