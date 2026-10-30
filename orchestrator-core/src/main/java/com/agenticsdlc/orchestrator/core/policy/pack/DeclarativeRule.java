package com.agenticsdlc.orchestrator.core.policy.pack;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * A rule of a {@link PolicyPack}: when a gate of the given phase (and stage) is evaluated and the condition matches,
 * the rule produces a policy finding with the effect's verdict.
 *
 * @param id rule id matching {@link #ID_PATTERN} (for example {@code ORG-001}); must not be a built-in rule id
 * @param description what the rule guards (may be empty)
 * @param phase the gate phase the rule applies to
 * @param stages stage ids the rule applies to (empty for every stage; must be empty for {@link GatePhase#RUN_START})
 * @param when the condition
 * @param effect what a match does
 * @param severity severity of the finding; null in the constructor means the effect's default (HIGH for DENY, MEDIUM
 *                 for REQUIRE_APPROVAL, LOW for WARN, which is always LOW)
 * @param message finding message (not blank)
 */
public record DeclarativeRule(String id, String description, GatePhase phase, List<String> stages, Condition when,
                              Effect effect, Severity severity, String message) {

    /** Pattern of declarative rule ids: two to five upper-case letters, a dash and three digits. */
    public static final Pattern ID_PATTERN = Pattern.compile("[A-Z]{2,5}-\\d{3}");

    /**
     * Validates the required fields, copies the stages and applies the default severity when {@code severity} is
     * null.
     */
    @SuppressWarnings("ConstantValue")
    public DeclarativeRule {
        Objects.requireNonNull(id, "id");
        description = Immutables.text(description);
        Objects.requireNonNull(phase, "phase");
        stages = Immutables.list(stages);
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(effect, "effect");
        severity = severity != null ? severity : effect.defaultSeverity();
        message = Immutables.text(message);
    }

    /**
     * Returns whether the rule applies to a gate.
     *
     * @param gatePhase the phase being evaluated
     * @param stageId the stage being evaluated, or null at run start
     * @return true when the phase matches and the stage is listed (or no stages are listed)
     */
    public boolean appliesTo(GatePhase gatePhase, @Nullable String stageId) {
        return phase == gatePhase && (stages.isEmpty() || stageId != null && stages.contains(stageId));
    }
}
