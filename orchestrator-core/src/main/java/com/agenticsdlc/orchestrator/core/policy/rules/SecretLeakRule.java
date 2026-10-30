package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;

/**
 * {@code SEC-002 SecretLeak} (EXIT of any stage): denies an artifact whose canonical JSON contains something
 * that looks like a secret. The finding names the kind of secret, never the value.
 */
public final class SecretLeakRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "SEC-002";

    private final JsonCodec codec;

    /**
     * Creates the rule with the standard codec.
     */
    public SecretLeakRule() {
        this(JsonCodec.standard());
    }

    /**
     * Creates the rule.
     *
     * @param codec codec used for canonical JSON
     */
    public SecretLeakRule(JsonCodec codec) {
        this.codec = codec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SecretLeak";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        if (ctx.phase() != GatePhase.EXIT || ctx.candidatePayload() == null) {
            return Optional.empty();
        }
        List<String> kinds = SecretPatterns.findKinds(codec.canonicalJson(ctx.candidatePayload()));
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.CRITICAL, Verdict.DENY,
                "artifact contains what looks like a secret: " + String.join(", ", kinds)));
    }
}
