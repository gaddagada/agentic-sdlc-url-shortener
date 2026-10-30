package com.agenticsdlc.orchestrator.core.evaluation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.UnifiedDiff;

/**
 * The deterministic scorers, one per artifact kind. Every criterion is computed from the artifacts (and, for REPO
 * runs, the codebase index); criteria that do not apply to a run get weight 0. Weights: 2 for the criterion that
 * matters most for the artifact, 1 for regular criteria, 0.5 for hygiene.
 */
public final class ArtifactScorers {

    private static final Map<ArtifactKind, ArtifactScorer<?>> ALL = all();

    private ArtifactScorers() {
    }

    private static Map<ArtifactKind, ArtifactScorer<?>> all() {
        Map<ArtifactKind, ArtifactScorer<?>> scorers = new EnumMap<>(ArtifactKind.class);
        for (ArtifactScorer<?> scorer : List.of(new RequirementSpecScorer(), new TaskPlanScorer(),
                new ImpactAnalysisScorer(), new DesignSpecScorer(), new ChangeSetScorer(), new TestPlanScorer(),
                new VerificationReportScorer(), new DocumentationScorer(), new SecurityReviewScorer(),
                new ReleaseReadinessScorer())) {
            scorers.put(scorer.kind(), scorer);
        }
        return Map.copyOf(scorers);
    }

    /**
     * Returns the scorer of an artifact kind.
     *
     * @param kind the kind
     * @return the scorer (every kind has one)
     */
    public static ArtifactScorer<?> forKind(ArtifactKind kind) {
        ArtifactScorer<?> scorer = ALL.get(kind);
        if (scorer == null) {
            throw new IllegalArgumentException("no scorer for " + kind);
        }
        return scorer;
    }

    /**
     * Returns every scorer by kind.
     *
     * @return the scorers
     */
    public static Map<ArtifactKind, ArtifactScorer<?>> scorers() {
        return ALL;
    }

    // =========================================================================================== helpers

    static Criterion criterion(String name, double weight, double value, String rationale) {
        return new Criterion(name, weight, value, rationale);
    }

    static double ratio(long part, long total) {
        return total == 0 ? 1.0 : (double) part / total;
    }

    static String fraction(long part, long total, String what) {
        return part + "/" + total + " " + what;
    }

    static <T> long count(List<T> items, Predicate<T> condition) {
        return items.stream().filter(condition).count();
    }

    static List<RequirementSpec.Requirement> functional(ScoringContext context) {
        return context.find(RequirementSpec.class).map(RequirementSpec::functional).orElse(List.of());
    }

    static List<RequirementSpec.Requirement> nonFunctional(ScoringContext context) {
        return context.find(RequirementSpec.class).map(RequirementSpec::nonFunctional).orElse(List.of());
    }

    // =========================================================================================== scorers

    /**
     * Requirement specification: every functional requirement has acceptance criteria (weight 2), measurable
     * non-functional requirements, ambiguity resolved, functional requirements present, preliminary risk with
     * factors.
     */
    public static final class RequirementSpecScorer implements ArtifactScorer<RequirementSpec> {

        /**
         * Creates the scorer.
         */
        public RequirementSpecScorer() {
            // stateless
        }

        @Override
        public Class<RequirementSpec> payloadType() {
            return RequirementSpec.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.REQUIREMENT_SPEC;
        }

        @Override
        public Score score(RequirementSpec spec, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            Set<String> traced = new HashSet<>();
            spec.acceptanceCriteria().forEach(ac -> traced.addAll(ac.traces()));
            long withAc = count(spec.functional(), r -> traced.contains(r.id()));
            criteria.add(criterion("acceptance-criteria-per-fr", 2, ratio(withAc, spec.functional().size()),
                    fraction(withAc, spec.functional().size(), "functional requirements have acceptance criteria")));
            criteria.add(criterion("functional-requirements", 1, spec.functional().isEmpty() ? 0 : 1,
                    spec.functional().size() + " functional requirements"));
            long measurable = count(spec.nonFunctional(), RequirementSpec.Requirement::measurable);
            criteria.add(criterion("measurable-nfrs", spec.nonFunctional().isEmpty() ? 0 : 1,
                    ratio(measurable, spec.nonFunctional().size()), spec.nonFunctional().isEmpty()
                            ? "no non-functional requirements stated"
                            : fraction(measurable, spec.nonFunctional().size(), "non-functional requirements are "
                            + "measurable")));
            double ambiguity = spec.classification() == RequirementSpec.Classification.AMBIGUOUS ? 0.0
                    : spec.openQuestions().isEmpty() ? 1.0 : 0.5;
            criteria.add(criterion("ambiguity-resolved", 1, ambiguity, "classification " + spec.classification()
                    + ", " + spec.openQuestions().size() + " open questions, ambiguity score "
                    + spec.ambiguity().score()));
            boolean riskExplained = spec.preliminaryRisk() != null && (spec.preliminaryRisk().level() == RiskLevel.LOW
                    || !spec.preliminaryRisk().factors().isEmpty());
            criteria.add(criterion("risk-assessed", 0.5, riskExplained ? 1 : 0, spec.preliminaryRisk() == null
                    ? "no preliminary risk" : "preliminary risk " + spec.preliminaryRisk().level() + " with "
                    + spec.preliminaryRisk().factors().size() + " factors"));
            return Score.of(criteria);
        }
    }

    /**
     * Task plan: every functional requirement is covered by a task (weight 2), dependencies point to earlier tasks,
     * impact analysis comes first in brownfield work, a human release gate exists, a critical path is given.
     */
    public static final class TaskPlanScorer implements ArtifactScorer<TaskPlan> {

        /**
         * Creates the scorer.
         */
        public TaskPlanScorer() {
            // stateless
        }

        @Override
        public Class<TaskPlan> payloadType() {
            return TaskPlan.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.TASK_PLAN;
        }

        @Override
        public Score score(TaskPlan plan, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            List<RequirementSpec.Requirement> functional = functional(context);
            Set<String> traced = new HashSet<>();
            plan.tasks().forEach(t -> traced.addAll(t.traces()));
            long covered = count(functional, r -> traced.contains(r.id()));
            criteria.add(criterion("fr-coverage", functional.isEmpty() ? 0 : 2, ratio(covered, functional.size()),
                    functional.isEmpty() ? "no requirement specification to compare with"
                            : fraction(covered, functional.size(), "functional requirements are covered by a task")));
            Set<String> seen = new HashSet<>();
            long ordered = 0;
            for (TaskPlan.Task task : plan.tasks()) {
                if (seen.containsAll(task.dependsOn())) {
                    ordered++;
                }
                seen.add(task.id());
            }
            criteria.add(criterion("dependency-order", 1, ratio(ordered, plan.tasks().size()),
                    fraction(ordered, plan.tasks().size(), "tasks depend only on tasks listed before them")));
            boolean brownfield = context.find(RequirementSpec.class).map(s -> s.classification()
                    == RequirementSpec.Classification.BROWNFIELD).orElse(false);
            List<TaskPlan.Task> implement = plan.tasks().stream().filter(t -> t.type() == TaskPlan.TaskType.IMPLEMENT)
                    .toList();
            long afterImpact = count(implement, t -> dependsOnAnalysis(plan, t));
            criteria.add(criterion("impact-first-in-brownfield", brownfield ? 1 : 0, ratio(afterImpact,
                    implement.size()), brownfield ? fraction(afterImpact, implement.size(), "implementation tasks "
                    + "come after an analysis task") : "not a brownfield change"));
            boolean releaseGate = plan.tasks().stream().anyMatch(t -> t.type() == TaskPlan.TaskType.GOVERNANCE
                    && t.owner() == TaskPlan.Owner.HUMAN);
            criteria.add(criterion("human-release-gate", 0.5, releaseGate ? 1 : 0, releaseGate
                    ? "a human-owned governance task exists" : "no human-owned governance task"));
            criteria.add(criterion("critical-path", 0.5, plan.criticalPath().isEmpty() ? 0 : 1,
                    plan.criticalPath().size() + " tasks on the critical path"));
            return Score.of(criteria);
        }

        private static boolean dependsOnAnalysis(TaskPlan plan, TaskPlan.Task task) {
            Map<String, TaskPlan.Task> byId = new HashMap<>();
            plan.tasks().forEach(t -> byId.put(t.id(), t));
            Deque<String> pending = new ArrayDeque<>(task.dependsOn());
            Set<String> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                TaskPlan.Task dependency = byId.get(pending.pop());
                if (dependency == null || !visited.add(dependency.id())) {
                    continue;
                }
                if (dependency.type() == TaskPlan.TaskType.ANALYSIS) {
                    return true;
                }
                pending.addAll(dependency.dependsOn());
            }
            return false;
        }
    }

    /**
     * Impact analysis: consistent with the workspace (existing codebase exactly when a REPO index exists), components
     * identified, paths of existing components found in the index, reasons given, tests suggested.
     */
    public static final class ImpactAnalysisScorer implements ArtifactScorer<ImpactAnalysis> {

        /**
         * Creates the scorer.
         */
        public ImpactAnalysisScorer() {
            // stateless
        }

        @Override
        public Class<ImpactAnalysis> payloadType() {
            return ImpactAnalysis.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.IMPACT_ANALYSIS;
        }

        @Override
        public Score score(ImpactAnalysis impact, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            boolean consistent = context.hasCodebase() ? impact.existingCodebase() && impact.indexedFiles() > 0
                    : !impact.existingCodebase();
            criteria.add(criterion("consistent-with-workspace", 1, consistent ? 1 : 0, "existingCodebase="
                    + impact.existingCodebase() + " with " + (context.hasCodebase() ? "an indexed workspace"
                    : "no indexed workspace")));
            criteria.add(criterion("components-identified", 2, impact.components().isEmpty() ? 0 : 1,
                    impact.components().size() + " components"));
            List<ImpactAnalysis.ImpactedComponent> existing = impact.components().stream()
                    .filter(c -> c.nature() != ImpactAnalysis.ChangeNature.NEW).toList();
            CodebaseIndex index = context.index();
            long known = index == null ? 0 : count(existing, c -> !c.path().isBlank() && index.containsPath(c.path()));
            criteria.add(criterion("paths-known", context.hasCodebase() && !existing.isEmpty() ? 1 : 0,
                    ratio(known, existing.size()), context.hasCodebase() ? fraction(known, existing.size(),
                    "existing components have a path in the index") : "no indexed workspace"));
            long reasoned = count(impact.components(), c -> !c.reasons().isEmpty());
            criteria.add(criterion("reasons-given", impact.components().isEmpty() ? 0 : 0.5,
                    ratio(reasoned, impact.components().size()), fraction(reasoned, impact.components().size(),
                            "components carry reasons")));
            criteria.add(criterion("suggested-tests", 0.5, impact.suggestedTests().isEmpty() ? 0 : 1,
                    impact.suggestedTests().size() + " suggested tests"));
            return Score.of(criteria);
        }
    }

    /**
     * Design: risk assessed with factors, decisions recorded with rationale, NFR strategies for every non-functional
     * requirement, existing components refer to types of the index (REPO runs), alternatives considered, personal
     * data handling defined.
     */
    public static final class DesignSpecScorer implements ArtifactScorer<DesignSpec> {

        /**
         * Creates the scorer.
         */
        public DesignSpecScorer() {
            // stateless
        }

        @Override
        public Class<DesignSpec> payloadType() {
            return DesignSpec.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.DESIGN_SPEC;
        }

        @Override
        public Score score(DesignSpec design, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            boolean riskExplained = design.risk() != null && (design.risk().level() == RiskLevel.LOW
                    || !design.risk().factors().isEmpty());
            criteria.add(criterion("risk-assessed-with-factors", 1, riskExplained ? 1 : 0, design.risk() == null
                    ? "no risk assessment" : "risk " + design.risk().level() + " with " + design.risk().factors()
                    .size() + " factors"));
            long reasoned = count(design.decisions(), d -> !d.rationale().isBlank() && !d.decision().isBlank());
            criteria.add(criterion("decisions-recorded", 1, design.decisions().isEmpty() ? 0
                    : ratio(reasoned, design.decisions().size()), fraction(reasoned, design.decisions().size(),
                    "decisions have a decision and a rationale")));
            List<RequirementSpec.Requirement> nfrs = nonFunctional(context);
            Set<String> strategies = new HashSet<>();
            design.nfrStrategies().forEach(s -> strategies.add(s.requirementId()));
            long withStrategy = count(nfrs, r -> strategies.contains(r.id()));
            criteria.add(criterion("nfr-strategies", nfrs.isEmpty() ? 0 : 1, ratio(withStrategy, nfrs.size()),
                    nfrs.isEmpty() ? "no non-functional requirements" : fraction(withStrategy, nfrs.size(),
                            "non-functional requirements have a strategy")));
            List<DesignSpec.Component> existing = design.components().stream().filter(c -> !c.isNew()).toList();
            CodebaseIndex index = context.index();
            long known = index == null ? 0 : count(existing, c -> index.find(c.name()).isPresent());
            criteria.add(criterion("known-types-in-repo", context.hasCodebase() && !existing.isEmpty() ? 1 : 0,
                    ratio(known, existing.size()), context.hasCodebase() ? fraction(known, existing.size(),
                    "existing components name a type of the index") : "no indexed workspace"));
            long chosen = count(design.alternativesConsidered(), DesignSpec.Alternative::chosen);
            boolean alternatives = design.alternativesConsidered().size() >= 2 && chosen == 1;
            criteria.add(criterion("alternatives-considered", 0.5, alternatives ? 1 : 0,
                    design.alternativesConsidered().size() + " alternatives, " + chosen + " chosen"));
            DesignSpec.DataHandling data = design.dataHandling();
            boolean pii = data != null && !data.piiFields().isEmpty();
            boolean protectedPii = pii && !data.protection().isBlank() && !data.retention().isBlank();
            criteria.add(criterion("data-handling", pii ? 0.5 : 0, !pii || protectedPii ? 1 : 0, pii
                    ? "personal data " + data.piiFields() + (protectedPii ? " with protection and retention"
                    : " without protection or retention") : "no personal data"));
            return Score.of(criteria);
        }
    }

    /**
     * Implementation (change set): content is complete (full or diff content scores 1, a skeleton 0.25, no content 0;
     * weight 2), production changes are accompanied by tests, diffs parse and target existing files, paths are
     * consistent with the index (MODIFY and DELETE of indexed files, CREATE of new files), a rollback plan exists.
     */
    public static final class ChangeSetScorer implements ArtifactScorer<ChangeSet> {

        /**
         * Creates the scorer.
         */
        public ChangeSetScorer() {
            // stateless
        }

        /** Value of a SKELETON change in {@code content-complete}. */
        public static final double SKELETON_VALUE = 0.25;

        @Override
        public Class<ChangeSet> payloadType() {
            return ChangeSet.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.CHANGE_SET;
        }

        @Override
        public Score score(ChangeSet changeSet, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            List<ChangeSet.FileChange> written = changeSet.changes().stream()
                    .filter(c -> c.operation() != ChangeSet.Operation.DELETE).toList();
            double content = 0.0;
            long skeletons = 0;
            for (ChangeSet.FileChange change : written) {
                switch (change.contentKind()) {
                    case FULL, DIFF -> content += 1.0;
                    case SKELETON -> {
                        content += SKELETON_VALUE;
                        skeletons++;
                    }
                    case NONE -> {
                        // no content
                    }
                }
            }
            criteria.add(criterion("content-complete", 2, written.isEmpty() ? 1.0 : content / written.size(),
                    written.size() + " written files, " + skeletons + " skeletons (a skeleton counts "
                            + SKELETON_VALUE + ")"));
            List<ChangeSet.FileChange> production = changeSet.changes().stream()
                    .filter(c -> c.operation() != ChangeSet.Operation.DELETE && c.path().contains("src/main/")
                            && c.path().endsWith(".java")).toList();
            long tested = count(production, p -> hasTest(changeSet, p));
            criteria.add(criterion("tests-accompany", production.isEmpty() ? 0 : 1, ratio(tested, production.size()),
                    fraction(tested, production.size(), "production Java changes have a test change")));
            List<ChangeSet.FileChange> diffs = changeSet.changes().stream()
                    .filter(c -> c.contentKind() == ChangeSet.ContentKind.DIFF).toList();
            CodebaseIndex index = context.hasCodebase() ? context.index() : null;
            long applicable = count(diffs, d -> parses(d.content()) && (index == null || index.containsPath(d.path())));
            criteria.add(criterion("diffs-applicable", diffs.isEmpty() ? 0 : 1, ratio(applicable, diffs.size()),
                    fraction(applicable, diffs.size(), "diffs parse" + (index == null ? "" : " and target an indexed "
                            + "file"))));
            long consistent = index == null ? 0 : count(changeSet.changes(), c -> c.operation()
                    == ChangeSet.Operation.CREATE ? !index.containsPath(c.path()) : index.containsPath(c.path()));
            criteria.add(criterion("paths-consistent-with-index", index == null ? 0 : 1,
                    ratio(consistent, changeSet.changes().size()), index == null ? "no indexed workspace"
                            : fraction(consistent, changeSet.changes().size(), "changes create new files or modify "
                            + "indexed files")));
            criteria.add(criterion("rollback-plan", 0.5, changeSet.rollbackPlan().isBlank() ? 0 : 1,
                    changeSet.rollbackPlan().isBlank() ? "no rollback plan" : "rollback plan present"));
            return Score.of(criteria);
        }

        private static boolean hasTest(ChangeSet changeSet, ChangeSet.FileChange production) {
            String type = production.fileName().replace(".java", "");
            return changeSet.changes().stream().anyMatch(c -> c.path().contains("src/test/")
                    && c.fileName().contains(type));
        }

        private static boolean parses(String diff) {
            try {
                UnifiedDiff.parse(diff);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    /**
     * Test plan: every functional requirement has a test case (weight 2), functional requirements have negative,
     * boundary, security or recovery cases, non-functional requirements are covered or listed as not covered, more
     * than one test level.
     */
    public static final class TestPlanScorer implements ArtifactScorer<TestPlan> {

        /**
         * Creates the scorer.
         */
        public TestPlanScorer() {
            // stateless
        }

        @Override
        public Class<TestPlan> payloadType() {
            return TestPlan.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.TEST_PLAN;
        }

        @Override
        public Score score(TestPlan plan, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            List<RequirementSpec.Requirement> functional = functional(context);
            long covered = count(functional, r -> covered(plan, r.id()));
            criteria.add(criterion("fr-coverage", functional.isEmpty() ? 0 : 2, ratio(covered, functional.size()),
                    functional.isEmpty() ? "no requirement specification to compare with"
                            : fraction(covered, functional.size(), "functional requirements have a test case")));
            long negative;
            String rationale;
            if (functional.isEmpty()) {
                negative = plan.cases().stream().anyMatch(c -> c.type() != TestPlan.CaseType.POSITIVE) ? 1 : 0;
                rationale = negative == 1 ? "negative cases present" : "only positive cases";
                criteria.add(criterion("negative-cases", 1, negative, rationale));
            } else {
                negative = count(functional, r -> plan.cases().stream().anyMatch(c -> c.traces().contains(r.id())
                        && c.type() != TestPlan.CaseType.POSITIVE));
                criteria.add(criterion("negative-cases", 1, ratio(negative, functional.size()),
                        fraction(negative, functional.size(), "functional requirements have a negative, boundary, "
                                + "security or recovery case")));
            }
            List<RequirementSpec.Requirement> nfrs = nonFunctional(context);
            long nfrCovered = count(nfrs, r -> covered(plan, r.id())
                    || plan.risksNotCovered().stream().anyMatch(n -> n.contains(r.id())));
            criteria.add(criterion("nfr-coverage", nfrs.isEmpty() ? 0 : 1, ratio(nfrCovered, nfrs.size()),
                    nfrs.isEmpty() ? "no non-functional requirements" : fraction(nfrCovered, nfrs.size(),
                            "non-functional requirements are tested or listed as not covered")));
            Set<TestPlan.Level> levels = new LinkedHashSet<>();
            plan.levels().forEach(l -> levels.add(l.level()));
            criteria.add(criterion("test-levels", 0.5, levels.size() >= 2 ? 1 : levels.size() == 1 ? 0.5 : 0,
                    levels.size() + " test levels"));
            return Score.of(criteria);
        }

        private static boolean covered(TestPlan plan, String requirementId) {
            return plan.cases().stream().anyMatch(c -> c.traces().contains(requirementId))
                    || !TestPlan.caseIds(plan.coverageMatrix().getOrDefault(requirementId, "")).isEmpty();
        }
    }

    /**
     * Verification: blocking checks passed (weight 2), verification passed, traceability complete, few advisory
     * findings ({@code 1 / (1 + count)}), a successful build (0.5 when no build ran, 0 for a failed build).
     */
    public static final class VerificationReportScorer implements ArtifactScorer<VerificationReport> {

        /**
         * Creates the scorer.
         */
        public VerificationReportScorer() {
            // stateless
        }

        @Override
        public Class<VerificationReport> payloadType() {
            return VerificationReport.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.VERIFICATION_REPORT;
        }

        @Override
        public Score score(VerificationReport report, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            List<Check> blocking = report.structuralChecks().stream().filter(Check::blocking).toList();
            long passed = count(blocking, Check::passed);
            criteria.add(criterion("blocking-checks-passed", 2, ratio(passed, blocking.size()),
                    fraction(passed, blocking.size(), "blocking checks passed")));
            criteria.add(criterion("verification-passed", 1, report.passed() ? 1 : 0, report.passed() ? "passed"
                    : "failed: " + String.join(", ", report.blockingFailures())));
            VerificationReport.Traceability trace = report.traceability();
            criteria.add(criterion("traceability", 1, trace == null ? 0 : ratio(trace.requirementsCovered(),
                    trace.requirementsTotal()), trace == null ? "no traceability" : fraction(
                    trace.requirementsCovered(), trace.requirementsTotal(), "requirements traced")));
            int advisory = report.advisoryFindings().size();
            criteria.add(criterion("advisory-findings", 0.5, 1.0 / (1 + advisory), advisory + " advisory findings"));
            VerificationReport.BuildOutcome build = report.build();
            double buildValue = build == null || !build.executed() ? 0.5 : build.exitCode() == 0 ? 1.0 : 0.0;
            criteria.add(criterion("build", 0.5, buildValue, build == null || !build.executed()
                    ? "no build executed (structural verification only)" : "build exit code " + build.exitCode()));
            return Score.of(criteria);
        }
    }

    /**
     * Documentation: all sections present (README, API reference, runbook, changelog, ADRs; weight 2), the API
     * reference names every API path of the design, the runbook explains the rollback, an ADR per design decision.
     */
    public static final class DocumentationScorer implements ArtifactScorer<DocumentationBundle> {

        /**
         * Creates the scorer.
         */
        public DocumentationScorer() {
            // stateless
        }

        @Override
        public Class<DocumentationBundle> payloadType() {
            return DocumentationBundle.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.DOCUMENTATION_BUNDLE;
        }

        @Override
        public Score score(DocumentationBundle docs, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            if (docs.readmeSection().isBlank()) {
                missing.add("README section");
            }
            if (docs.apiReference().isBlank()) {
                missing.add("API reference");
            }
            if (docs.operationsRunbook().isBlank()) {
                missing.add("runbook");
            }
            if (docs.changelogEntry().isBlank()) {
                missing.add("changelog");
            }
            if (docs.adrs().isEmpty()) {
                missing.add("ADRs");
            }
            criteria.add(criterion("sections-present", 2, (5 - missing.size()) / 5.0, missing.isEmpty()
                    ? "all 5 sections present" : "missing: " + String.join(", ", missing)));
            Optional<DesignSpec> design = context.find(DesignSpec.class);
            List<DesignSpec.ApiChange> apis = design.map(DesignSpec::apiChanges).orElse(List.of());
            long documented = count(apis, a -> docs.apiReference().contains(a.path()));
            criteria.add(criterion("api-reference-covers-design", apis.isEmpty() ? 0 : 1, ratio(documented,
                    apis.size()), fraction(documented, apis.size(), "design API paths appear in the API reference")));
            String runbook = docs.operationsRunbook().toLowerCase(Locale.ROOT);
            boolean rollback = runbook.contains("rollback") || runbook.contains("roll back");
            criteria.add(criterion("runbook-rollback", 0.5, rollback ? 1 : 0, rollback ? "runbook explains the "
                    + "rollback" : "runbook does not mention a rollback"));
            int decisions = design.map(d -> d.decisions().size()).orElse(0);
            criteria.add(criterion("adrs-match-decisions", decisions == 0 ? 0 : 0.5,
                    decisions == 0 ? 1 : Math.min(1.0, (double) docs.adrs().size() / decisions),
                    docs.adrs().size() + " ADRs for " + decisions + " design decisions"));
            return Score.of(criteria);
        }
    }

    /**
     * Security review: no unmitigated HIGH or CRITICAL finding (weight 2), STRIDE categories covered (of 6), threats
     * mitigated or accepted, checks passed, a passing dependency inventory check when one exists.
     */
    public static final class SecurityReviewScorer implements ArtifactScorer<SecurityReview> {

        /**
         * Creates the scorer.
         */
        public SecurityReviewScorer() {
            // stateless
        }

        @Override
        public Class<SecurityReview> payloadType() {
            return SecurityReview.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.SECURITY_REVIEW;
        }

        @Override
        public Score score(SecurityReview review, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            long high = count(review.findings(), f -> f.severity() != null && f.severity().atLeast(Severity.HIGH));
            criteria.add(criterion("no-unmitigated-high", 2, high == 0 ? 1 : 0, high + " HIGH or CRITICAL findings"));
            Set<SecurityReview.StrideCategory> categories = new HashSet<>();
            review.threats().forEach(t -> categories.add(t.category()));
            int all = SecurityReview.StrideCategory.values().length;
            criteria.add(criterion("stride-coverage", 1, (double) categories.size() / all,
                    categories.size() + "/" + all + " STRIDE categories covered"));
            long treated = count(review.threats(), t -> t.status() != SecurityReview.ThreatStatus.OPEN);
            criteria.add(criterion("threats-treated", review.threats().isEmpty() ? 0 : 1, ratio(treated,
                    review.threats().size()), fraction(treated, review.threats().size(), "threats mitigated or "
                    + "accepted")));
            long passed = count(review.checks(), Check::passed);
            criteria.add(criterion("checks-passed", 1, ratio(passed, review.checks().size()),
                    fraction(passed, review.checks().size(), "checks passed")));
            Optional<Check> inventory = review.checks().stream()
                    .filter(c -> c.name().equals("dependency-inventory")).findFirst();
            criteria.add(criterion("dependency-inventory", inventory.isPresent() ? 0.5 : 0,
                    inventory.map(c -> c.passed() ? 1.0 : 0.0).orElse(1.0), inventory.map(Check::details)
                            .orElse("no dependency inventory (no workspace)")));
            return Score.of(criteria);
        }
    }

    /**
     * Release readiness: the decision is consistent with its inputs (weight 2: NO_GO when a checklist item, the
     * verification or the security review failed; else GO_WITH_CONDITIONS when there are conditions, MEDIUM
     * security findings or skeleton changes; else GO), checklist items pass with evidence, a rollback plan and
     * release notes exist.
     */
    public static final class ReleaseReadinessScorer implements ArtifactScorer<ReleaseReadiness> {

        /**
         * Creates the scorer.
         */
        public ReleaseReadinessScorer() {
            // stateless
        }

        @Override
        public Class<ReleaseReadiness> payloadType() {
            return ReleaseReadiness.class;
        }

        @Override
        public ArtifactKind kind() {
            return ArtifactKind.RELEASE_READINESS;
        }

        @Override
        public Score score(ReleaseReadiness release, ScoringContext context) {
            List<Criterion> criteria = new ArrayList<>();
            ReleaseReadiness.GoNoGo expected = expectedDecision(release, context);
            criteria.add(criterion("decision-consistent", 2, release.decision() == expected ? 1 : 0, "decision "
                    + release.decision() + ", expected " + expected + " from the checklist, verification, security "
                    + "review and change set"));
            long complete = count(release.checklist(), i -> i.status() != ReleaseReadiness.ChecklistStatus.FAIL
                    && !i.evidence().isBlank());
            criteria.add(criterion("checklist-complete", 1, ratio(complete, release.checklist().size()),
                    fraction(complete, release.checklist().size(), "checklist items pass (or are not applicable) "
                            + "with evidence")));
            boolean rollback = release.changeRecord() != null && !release.changeRecord().rollbackPlan().isBlank();
            criteria.add(criterion("rollback-plan", 0.5, rollback ? 1 : 0, rollback ? "rollback plan in the change "
                    + "record" : "no rollback plan"));
            criteria.add(criterion("release-notes", 0.5, release.releaseNotes().isBlank() ? 0 : 1,
                    release.releaseNotes().isBlank() ? "no release notes" : "release notes present"));
            return Score.of(criteria);
        }

        /**
         * Returns the decision the inputs call for.
         *
         * @param release the release readiness
         * @param context the run context
         * @return the expected decision
         */
        public static ReleaseReadiness.GoNoGo expectedDecision(ReleaseReadiness release, ScoringContext context) {
            boolean failedItem = release.checklist().stream()
                    .anyMatch(i -> i.status() == ReleaseReadiness.ChecklistStatus.FAIL);
            boolean verificationFailed = context.find(VerificationReport.class).map(v -> !v.passed()).orElse(false);
            Optional<SecurityReview> security = context.find(SecurityReview.class);
            boolean securityFailed = security.map(s -> !s.passed()).orElse(false);
            if (failedItem || verificationFailed || securityFailed) {
                return ReleaseReadiness.GoNoGo.NO_GO;
            }
            boolean mediumFindings = security.map(s -> s.findings().stream()
                    .anyMatch(f -> f.severity() == Severity.MEDIUM)).orElse(false);
            boolean skeleton = context.find(ChangeSet.class).map(c -> c.changes().stream()
                    .anyMatch(f -> f.contentKind() == ChangeSet.ContentKind.SKELETON)).orElse(false);
            return !release.conditions().isEmpty() || mediumFindings || skeleton
                    ? ReleaseReadiness.GoNoGo.GO_WITH_CONDITIONS : ReleaseReadiness.GoNoGo.GO;
        }
    }
}
