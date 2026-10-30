package com.agenticsdlc.orchestrator.core.policy.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Clarification;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.rules.Globs;

/**
 * Adapts a {@link DeclarativeRule} of a {@link PolicyPack} to the {@link PolicyRule} interface: the rule's condition
 * is compiled once (regexes and globs), and a match yields a finding with the rule's id, severity, the effect's
 * verdict and the message prefixed with the pack name and version. See {@link Condition} for what each condition
 * inspects.
 *
 * <p>Every regex search has a time budget of one second: a search that exceeds it (catastrophic backtracking)
 * throws, and the {@link com.agenticsdlc.orchestrator.core.policy.PolicyEngine} fails closed with a DENY finding for
 * the rule instead of stalling the gate.</p>
 */
public final class DeclarativePolicyRule implements PolicyRule {

    private final DeclarativeRule rule;
    private final String prefix;
    private final Predicate<PolicyContext> condition;

    /**
     * Compiles a rule of a pack.
     *
     * @param pack the pack the rule belongs to (its name and version label the findings)
     * @param rule the rule
     * @throws java.util.regex.PatternSyntaxException when a regex is invalid (validate the pack first)
     */
    public DeclarativePolicyRule(PolicyPack pack, DeclarativeRule rule) {
        Objects.requireNonNull(pack, "pack");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.prefix = "[" + pack.name() + "@" + pack.version() + "] ";
        this.condition = compile(rule.when());
    }

    /**
     * Returns the declarative rule.
     *
     * @return the rule
     */
    public DeclarativeRule rule() {
        return rule;
    }

    @Override
    public String id() {
        return rule.id();
    }

    @Override
    public String name() {
        return rule.description().isBlank() ? rule.id() : rule.description();
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        if (!rule.appliesTo(ctx.phase(), ctx.stageId()) || !condition.test(ctx)) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(rule.id(), rule.severity(), rule.effect().verdict(),
                prefix + rule.message()));
    }

    // =========================================================================================== compilation

    static Predicate<PolicyContext> compile(Condition condition) {
        return switch (condition) {
            case Condition.IntentMatches m -> {
                List<Pattern> patterns = patterns(m.regexes(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                yield ctx -> intentTexts(ctx).stream().anyMatch(text -> find(patterns, text));
            }
            case Condition.PayloadMatches m -> {
                List<Pattern> patterns = patterns(m.regexes(), 0);
                yield ctx -> ctx.candidatePayload() != null
                        && find(patterns, JsonCodec.standard().strictCanonicalJson(ctx.candidatePayload()));
            }
            case Condition.PathMatches m -> {
                List<Pattern> patterns = m.globs().stream().map(Globs::toPattern).toList();
                yield ctx -> paths(ctx).stream().map(Globs::normalize)
                        .anyMatch(path -> patterns.stream().anyMatch(p -> p.matcher(path).matches()));
            }
            case Condition.RiskAtLeast r -> ctx -> ctx.run().currentRisk().level().atLeast(r.level());
            case Condition.ChangeSizeOver s -> ctx -> {
                ChangeSet changeSet = changeSet(ctx);
                return changeSet != null && (changeSet.changes().size() > s.files()
                        || changeSet.totalContentLines() > s.lines());
            };
            case Condition.ReleaseDecisionIs d -> ctx -> {
                ReleaseReadiness release = release(ctx);
                return release != null && release.decision() == d.decision();
            };
            case Condition.AutonomyIs a -> ctx -> ctx.autonomy() == a.autonomy();
            case Condition.AllOf all -> {
                List<Predicate<PolicyContext>> parts = all.conditions().stream().map(DeclarativePolicyRule::compile)
                        .toList();
                yield ctx -> parts.stream().allMatch(p -> p.test(ctx));
            }
            case Condition.AnyOf any -> {
                List<Predicate<PolicyContext>> parts = any.conditions().stream().map(DeclarativePolicyRule::compile)
                        .toList();
                yield ctx -> parts.stream().anyMatch(p -> p.test(ctx));
            }
            case Condition.Not not -> compile(not.condition()).negate();
        };
    }

    private static List<Pattern> patterns(List<String> regexes, int flags) {
        return regexes.stream().map(r -> Pattern.compile(r, flags)).toList();
    }

    private static boolean find(List<Pattern> patterns, String text) {
        return patterns.stream().anyMatch(p -> RegexGuard.find(p, text));
    }

    /**
     * The texts an intent condition inspects: the requirement under evaluation, the run's clarification answers and,
     * at the exit of the requirements stage, the candidate specification's normalized statement.
     */
    static List<String> intentTexts(PolicyContext ctx) {
        List<String> texts = new ArrayList<>();
        texts.add(ctx.requirement());
        Clarification clarification = ctx.run().clarification();
        if (clarification != null) {
            texts.addAll(clarification.answers().values());
        }
        RequirementSpec spec = ctx.candidate(RequirementSpec.class);
        if (spec != null) {
            texts.add(spec.normalizedStatement());
        }
        return texts;
    }

    /**
     * The paths a path condition inspects: the candidate change set's file paths, else the candidate impact analysis'
     * component paths (regression risks excluded), else the paths of the run's accepted change set and impact
     * analysis.
     */
    static List<String> paths(PolicyContext ctx) {
        ChangeSet candidateChanges = ctx.candidate(ChangeSet.class);
        if (candidateChanges != null) {
            return changePaths(candidateChanges);
        }
        ImpactAnalysis candidateImpact = ctx.candidate(ImpactAnalysis.class);
        if (candidateImpact != null) {
            return impactPaths(candidateImpact);
        }
        List<String> paths = new ArrayList<>();
        ctx.run().acceptedPayload(ChangeSet.class).ifPresent(c -> paths.addAll(changePaths(c)));
        ctx.run().acceptedPayload(ImpactAnalysis.class).ifPresent(i -> paths.addAll(impactPaths(i)));
        return paths;
    }

    private static List<String> changePaths(ChangeSet changeSet) {
        return changeSet.changes().stream().map(ChangeSet.FileChange::path).filter(p -> !p.isBlank()).toList();
    }

    private static List<String> impactPaths(ImpactAnalysis impact) {
        return impact.components().stream().filter(c -> c.nature() != ImpactAnalysis.ChangeNature.REGRESSION_RISK)
                .map(ImpactAnalysis.ImpactedComponent::path).filter(p -> !p.isBlank()).toList();
    }

    private static @Nullable ChangeSet changeSet(PolicyContext ctx) {
        ChangeSet candidate = ctx.candidate(ChangeSet.class);
        return candidate != null ? candidate : ctx.run().acceptedPayload(ChangeSet.class).orElse(null);
    }

    private static @Nullable ReleaseReadiness release(PolicyContext ctx) {
        ReleaseReadiness candidate = ctx.candidate(ReleaseReadiness.class);
        return candidate != null ? candidate : ctx.run().acceptedPayload(ReleaseReadiness.class).orElse(null);
    }
}
