package com.agenticsdlc.orchestrator.core.model;

import java.util.Objects;

/**
 * A fault to inject into a stage's primary agent (fault-injection scenarios and tests). The first
 * {@code failTimes} primary-agent attempts of the stage fail with {@code kind}; the deterministic fallback
 * agent is never faulted.
 *
 * @param stageId target stage id
 * @param failTimes number of attempts to fail (at least 1)
 * @param kind kind of failure
 */
public record FaultSpec(String stageId, int failTimes, FaultKind kind) {

    /**
     * Validates the specification.
     */
    public FaultSpec {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(kind, "kind");
        if (failTimes < 1) {
            throw new IllegalArgumentException("failTimes must be at least 1");
        }
    }
}
