package com.agenticsdlc.orchestrator.core.util;

import java.util.UUID;

/**
 * Identifier generation. Runs, artifacts, approvals, checkpoints and compensations use random UUIDs.
 */
public final class Ids {

    private Ids() {
    }

    /**
     * Returns a new random identifier ({@code UUID.randomUUID().toString()}).
     *
     * @return a new identifier
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
