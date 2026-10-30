package com.agenticsdlc.orchestrator.core.engine;

import com.agenticsdlc.orchestrator.core.model.Compensation;

/**
 * Executes compensations of one action type during rollback or abort (saga pattern). A failure is audited
 * as {@code COMPENSATION_FAILED} and turns the halt reason into {@code ROLLBACK_FAILED}.
 */
public interface CompensationHandler {

    /**
     * Returns the action this handler executes, for example {@code DELETE_SANDBOX}.
     *
     * @return action name
     */
    String action();

    /**
     * Executes the compensation.
     *
     * @param compensation the compensation
     * @throws Exception on failure
     */
    void compensate(Compensation compensation) throws Exception;
}
