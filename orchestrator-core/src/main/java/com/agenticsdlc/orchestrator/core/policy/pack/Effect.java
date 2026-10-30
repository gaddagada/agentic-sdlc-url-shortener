package com.agenticsdlc.orchestrator.core.policy.pack;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * What a matching declarative rule does, mapped onto the engine's {@link Verdict}s.
 */
public enum Effect {
    /** The gate denies: the run safe-stops ({@link Verdict#DENY}); default severity HIGH. */
    DENY(Verdict.DENY, Severity.HIGH),
    /** The gate requires a human approval ({@link Verdict#REQUIRE_APPROVAL}); default severity MEDIUM. */
    REQUIRE_APPROVAL(Verdict.REQUIRE_APPROVAL, Severity.MEDIUM),
    /**
     * A warning that never blocks: an {@link Verdict#ALLOW} finding with severity LOW, audited as a policy finding
     * and shown as a gate note.
     */
    WARN(Verdict.ALLOW, Severity.LOW);

    private final Verdict verdict;
    private final Severity defaultSeverity;

    Effect(Verdict verdict, Severity defaultSeverity) {
        this.verdict = verdict;
        this.defaultSeverity = defaultSeverity;
    }

    /**
     * Returns the verdict of a finding with this effect.
     *
     * @return the verdict
     */
    public Verdict verdict() {
        return verdict;
    }

    /**
     * Returns the severity used when a rule declares none (always LOW for {@link #WARN}).
     *
     * @return the default severity
     */
    public Severity defaultSeverity() {
        return defaultSeverity;
    }
}
