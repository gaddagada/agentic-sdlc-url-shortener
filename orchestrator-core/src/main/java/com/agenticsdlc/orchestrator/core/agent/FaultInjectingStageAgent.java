package com.agenticsdlc.orchestrator.core.agent;

import java.util.Objects;

import com.agenticsdlc.orchestrator.core.model.FaultKind;

/**
 * Wraps a stage's primary agent to inject a configured fault. The engine decides under the run lock whether
 * an attempt is faulted (the per-run injection count is part of the run state, so injection is
 * deterministic and survives restarts) and wraps the agent only for faulted attempts; the deterministic
 * fallback agent is never wrapped.
 *
 * <p>TIMEOUT is simulated (the attempt reports a timeout immediately) so that fault scenarios do not wait
 * for the real stage timeout; real timeouts are enforced by the engine independently.</p>
 */
public final class FaultInjectingStageAgent implements StageAgent {

    private final StageAgent delegate;
    private final FaultKind kind;
    private final int injection;
    private final int failTimes;

    /**
     * Creates the wrapper.
     *
     * @param delegate the real agent
     * @param kind fault kind
     * @param injection 1-based number of this injection
     * @param failTimes total injections configured
     */
    public FaultInjectingStageAgent(StageAgent delegate, FaultKind kind, int injection, int failTimes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.injection = injection;
        this.failTimes = failTimes;
    }

    @Override
    public AgentRole role() {
        return delegate.role();
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        String message = "injected " + kind + " fault " + injection + "/" + failTimes + " on stage "
                + context.stageId();
        FailureKind failureKind = switch (kind) {
            case TRANSIENT -> FailureKind.TRANSIENT;
            case FATAL -> FailureKind.FATAL;
            case TIMEOUT -> FailureKind.TIMEOUT;
            case INVALID_OUTPUT -> FailureKind.INVALID_OUTPUT;
        };
        throw new StageFailure(failureKind, message);
    }
}
