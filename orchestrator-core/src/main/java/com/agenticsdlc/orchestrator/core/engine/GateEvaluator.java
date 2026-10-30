package com.agenticsdlc.orchestrator.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.graph.ApprovalRequirement;
import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyDecision;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * Evaluates entry and exit gates: the policy decision combined with the stage's {@link ApprovalRequirement}
 * and the run's autonomy (DENY > REQUIRE_APPROVAL > ALLOW).
 *
 * <p>RISK_BASED: never in FULL_AUTO; always in STRICT; in GUIDED when the run's current risk is at least
 * MEDIUM and exceeds the highest risk level already accepted by a human approval in the current plan
 * version (an approved design at risk HIGH covers later RISK_BASED gates at risk HIGH, so a human is asked
 * again only when the risk escalates or the plan changes). ALWAYS: approval regardless of autonomy.</p>
 *
 * <p>The run's risk includes the engine's deterministic {@link com.agenticsdlc.orchestrator.core.artifact.RiskFloor}
 * (see {@link Run#currentRisk()}). Exit gates are evaluated <em>before</em> the candidate artifact is accepted, on a
 * prospective copy of the run in which it is accepted: policy rules and the RISK_BASED requirement therefore see
 * the risk the run would have once the artifact under review is accepted, and that is the risk level an exit
 * approval records.</p>
 */
final class GateEvaluator {

    /**
     * Outcome of a gate.
     *
     * @param verdict combined verdict
     * @param findings policy findings
     * @param reasons reasons for requiring approval or denying (rule id prefixed)
     * @param notes informational notes (for example accepted risk)
     * @param risk the run risk the gate was evaluated with (prospective for exit gates)
     */
    record GateDecision(Verdict verdict, List<PolicyFinding> findings, List<String> reasons, List<String> notes,
                        RiskAssessment risk) {

        List<String> ruleIds() {
            return findings.stream().filter(f -> f.verdict() != Verdict.ALLOW).map(PolicyFinding::ruleId).toList();
        }

        String reasonText() {
            return String.join("; ", reasons);
        }
    }

    private final PolicyEngine policyEngine;

    GateEvaluator(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    PolicyDecision runStart(Run run, String requirement) {
        return policyEngine.evaluate(PolicyContext.runStart(run, requirement));
    }

    GateDecision entry(Run run, StageDefinition stage) {
        PolicyDecision policy = policyEngine.evaluate(PolicyContext.entry(run, stage.id(), stage.role()));
        return combine(run, policy, stage.gates().entryApproval(), "entry");
    }

    /**
     * Evaluates the exit gate of a candidate artifact that is in the run's history but not accepted yet.
     */
    GateDecision exit(Run run, StageDefinition stage, Artifact candidate) {
        Run prospective = run.copy();
        prospective.accept(stage.id(), candidate.version());
        PolicyDecision policy = policyEngine.evaluate(PolicyContext.exit(prospective, stage.id(), stage.role(),
                candidate.payload()));
        return combine(prospective, policy, stage.gates().exitApproval(), "exit");
    }

    private GateDecision combine(Run run, PolicyDecision policy, ApprovalRequirement requirement, String gate) {
        Verdict verdict = policy.verdict();
        RiskAssessment risk = run.currentRisk();
        List<String> reasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (PolicyFinding finding : policy.findings()) {
            if (finding.verdict() != Verdict.ALLOW) {
                reasons.add(finding.ruleId() + ": " + finding.message());
            } else {
                notes.add(finding.ruleId() + ": " + finding.message());
            }
        }
        switch (requirement) {
            case ALWAYS -> {
                verdict = Verdict.max(verdict, Verdict.REQUIRE_APPROVAL);
                reasons.add(gate + " gate ALWAYS requires approval");
            }
            case RISK_BASED -> {
                Optional<String> reason = riskBasedReason(run, risk, notes);
                if (reason.isPresent()) {
                    verdict = Verdict.max(verdict, Verdict.REQUIRE_APPROVAL);
                    reasons.add(gate + " gate RISK_BASED: " + reason.get());
                }
            }
            case NEVER -> {
                // no approval from the gate itself
            }
        }
        return new GateDecision(verdict, policy.findings(), reasons, notes, risk);
    }

    private static Optional<String> riskBasedReason(Run run, RiskAssessment risk, List<String> notes) {
        AutonomyLevel autonomy = run.options().autonomy();
        if (autonomy == AutonomyLevel.FULL_AUTO) {
            notes.add("RISK_BASED gate skipped in FULL_AUTO autonomy");
            return Optional.empty();
        }
        if (autonomy == AutonomyLevel.STRICT) {
            return Optional.of("STRICT autonomy requires approval (risk " + risk.level() + ")");
        }
        if (!risk.level().atLeast(RiskLevel.MEDIUM)) {
            notes.add("risk " + risk.level() + " is below MEDIUM");
            return Optional.empty();
        }
        Optional<Approval> covering = run.approvals().stream()
                .filter(a -> a.status() == ApprovalStatus.APPROVED && a.planVersion() == run.planVersion())
                .filter(a -> a.riskLevel().atLeast(risk.level()))
                .findFirst();
        if (covering.isPresent()) {
            notes.add("risk " + risk.level() + " already accepted by the approval of " + covering.get().stageId()
                    + " in plan v" + run.planVersion());
            return Optional.empty();
        }
        String factors = risk.factors().isEmpty() ? "" : " (" + String.join(", ", risk.factors()) + ")";
        return Optional.of("risk " + risk.level() + factors + " requires approval in GUIDED autonomy");
    }
}
