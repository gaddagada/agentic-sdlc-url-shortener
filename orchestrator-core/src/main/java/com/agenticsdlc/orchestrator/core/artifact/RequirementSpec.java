package com.agenticsdlc.orchestrator.core.artifact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code intake} stage: the normalized, structured understanding of the requirement.
 *
 * @param normalizedStatement the requirement with normalized whitespace (plus merged clarification answers)
 * @param intent one-line statement of intent
 * @param classification greenfield, brownfield or ambiguous
 * @param scopeIn areas in scope
 * @param scopeOut areas explicitly out of scope
 * @param functional functional requirements ({@code FR-n})
 * @param nonFunctional non-functional requirements ({@code NFR-n})
 * @param acceptanceCriteria Given/When/Then acceptance criteria ({@code AC-n})
 * @param assumptions assumptions made
 * @param openQuestions questions still open
 * @param ambiguity ambiguity report
 * @param preliminaryRisk preliminary risk assessment
 */
public record RequirementSpec(
        String normalizedStatement,
        String intent,
        Classification classification,
        List<String> scopeIn,
        List<String> scopeOut,
        List<Requirement> functional,
        List<Requirement> nonFunctional,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<String> assumptions,
        List<String> openQuestions,
        AmbiguityReport ambiguity,
        RiskAssessment preliminaryRisk) implements ArtifactPayload {

    /** Allowed non-functional categories. */
    public static final Set<String> NFR_CATEGORIES = Set.of(
            "PERFORMANCE", "RELIABILITY", "SECURITY", "COMPLIANCE", "OPERABILITY", "USABILITY", "OTHER");

    /** Category used for functional requirements. */
    public static final String FUNCTIONAL_CATEGORY = "FUNCTIONAL";

    /**
     * Normalizes collections and text.
     */
    public RequirementSpec {
        normalizedStatement = Immutables.text(normalizedStatement);
        intent = Immutables.text(intent);
        scopeIn = Immutables.list(scopeIn);
        scopeOut = Immutables.list(scopeOut);
        functional = Immutables.list(functional);
        nonFunctional = Immutables.list(nonFunctional);
        acceptanceCriteria = Immutables.list(acceptanceCriteria);
        assumptions = Immutables.list(assumptions);
        openQuestions = Immutables.list(openQuestions);
    }

    /** Requirement classification. */
    public enum Classification {
        /** New system or module. */
        GREENFIELD,
        /** Change to an existing codebase. */
        BROWNFIELD,
        /** Too ambiguous to plan without clarification. */
        AMBIGUOUS
    }

    /** MoSCoW-style priority. */
    public enum Priority {
        /** Mandatory. */
        MUST,
        /** Important. */
        SHOULD,
        /** Nice to have. */
        COULD
    }

    /**
     * A single requirement.
     *
     * @param id {@code FR-n} or {@code NFR-n}
     * @param text requirement text
     * @param priority priority
     * @param category {@code FUNCTIONAL} for functional requirements, otherwise one of {@link #NFR_CATEGORIES}
     * @param measurable whether the requirement carries a measurable target
     */
    public record Requirement(String id, String text, Priority priority, String category, boolean measurable) {
        /**
         * Normalizes text.
         */
        public Requirement {
            id = Immutables.text(id);
            text = Immutables.text(text);
            category = Immutables.text(category);
        }
    }

    /**
     * A Given/When/Then acceptance criterion.
     *
     * @param id {@code AC-n}
     * @param given precondition
     * @param when action
     * @param then expected outcome
     * @param traces requirement ids covered
     */
    public record AcceptanceCriterion(String id, String given, String when, String then, List<String> traces) {
        /**
         * Normalizes fields.
         */
        public AcceptanceCriterion {
            id = Immutables.text(id);
            given = Immutables.text(given);
            when = Immutables.text(when);
            then = Immutables.text(then);
            traces = Immutables.list(traces);
        }
    }

    /**
     * Ambiguity assessment of the requirement.
     *
     * @param score 0..1, higher is more ambiguous
     * @param findings fired ambiguity findings
     * @param questions clarification questions derived from the findings
     */
    public record AmbiguityReport(double score, List<AmbiguityFinding> findings, List<ClarificationQuestion> questions) {
        /**
         * Normalizes collections.
         */
        public AmbiguityReport {
            findings = Immutables.list(findings);
            questions = Immutables.list(questions);
        }
    }

    /**
     * A fired ambiguity finding.
     *
     * @param code finding code, for example {@code VAGUE_QUALIFIER_WITHOUT_METRIC}
     * @param description explanation
     * @param weight contribution to the score
     */
    public record AmbiguityFinding(String code, String description, double weight) {
        /**
         * Normalizes text.
         */
        public AmbiguityFinding {
            code = Immutables.text(code);
            description = Immutables.text(description);
        }
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.REQUIREMENT_SPEC;
    }

    /**
     * Returns functional and non-functional requirements in order.
     *
     * @return all requirements
     */
    public List<Requirement> allRequirements() {
        List<Requirement> all = new ArrayList<>(functional);
        all.addAll(nonFunctional);
        return List.copyOf(all);
    }

    @Override
    public void validate() {
        Problems p = new Problems("RequirementSpec");
        p.requireText(normalizedStatement, "normalizedStatement");
        p.requireText(intent, "intent");
        p.requireNonNull(classification, "classification");
        p.requireNonEmpty(functional, "functional");
        p.requireNonNull(ambiguity, "ambiguity");
        p.requireNonNull(preliminaryRisk, "preliminaryRisk");
        Set<String> ids = new HashSet<>();
        for (Requirement r : functional) {
            p.requirePattern(r.id(), "FR-\\d+", "functional requirement id");
            p.requireText(r.text(), "requirement " + r.id() + " text");
            p.requireNonNull(r.priority(), "requirement " + r.id() + " priority");
            ids.add(r.id());
        }
        for (Requirement r : nonFunctional) {
            p.requirePattern(r.id(), "NFR-\\d+", "non-functional requirement id");
            p.requireText(r.text(), "requirement " + r.id() + " text");
            p.requireNonNull(r.priority(), "requirement " + r.id() + " priority");
            p.require(NFR_CATEGORIES.contains(r.category()),
                    "requirement " + r.id() + " category '" + r.category() + "' must be one of " + NFR_CATEGORIES);
            ids.add(r.id());
        }
        p.requireUnique(allRequirements(), Requirement::id, "requirements");
        p.requireUnique(acceptanceCriteria, AcceptanceCriterion::id, "acceptanceCriteria");
        for (AcceptanceCriterion ac : acceptanceCriteria) {
            p.requirePattern(ac.id(), "AC-\\d+", "acceptance criterion id");
            p.requireText(ac.then(), "acceptance criterion " + ac.id() + " then");
            p.requireNonEmpty(ac.traces(), "acceptance criterion " + ac.id() + " traces");
            for (String trace : ac.traces()) {
                p.require(ids.contains(trace), "acceptance criterion " + ac.id() + " traces unknown requirement " + trace);
            }
        }
        if (ambiguity != null) {
            p.require(ambiguity.score() >= 0.0 && ambiguity.score() <= 1.0, "ambiguity score must be within 0..1");
            p.requireUnique(ambiguity.questions(), ClarificationQuestion::id, "ambiguity questions");
            for (ClarificationQuestion q : ambiguity.questions()) {
                p.requireText(q.id(), "question id");
                p.requireText(q.question(), "question " + q.id() + " text");
            }
        }
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Requirement specification")
                .field("Classification", classification)
                .field("Intent", intent)
                .paragraph("> " + Markdown.inline(normalizedStatement));
        md.heading(3, "Scope").paragraph("**In scope**").bullets(scopeIn).paragraph("**Out of scope**").bullets(scopeOut);
        md.heading(3, "Functional requirements").table(List.of("ID", "Priority", "Requirement", "Measurable"),
                functional.stream().map(r -> List.of(r.id(), String.valueOf(r.priority()), r.text(),
                        r.measurable() ? "yes" : "no")).toList());
        md.heading(3, "Non-functional requirements").table(List.of("ID", "Category", "Priority", "Requirement",
                        "Measurable"),
                nonFunctional.stream().map(r -> List.of(r.id(), r.category(), String.valueOf(r.priority()), r.text(),
                        r.measurable() ? "yes" : "no")).toList());
        md.heading(3, "Acceptance criteria").table(List.of("ID", "Given", "When", "Then", "Traces"),
                acceptanceCriteria.stream().map(a -> List.of(a.id(), a.given(), a.when(), a.then(),
                        Markdown.join(a.traces()))).toList());
        md.heading(3, "Assumptions").bullets(assumptions);
        md.heading(3, "Open questions").bullets(openQuestions);
        if (ambiguity != null) {
            md.heading(3, "Ambiguity").field("Score", String.format(java.util.Locale.ROOT, "%.2f", ambiguity.score()));
            md.table(List.of("Finding", "Weight", "Description"), ambiguity.findings().stream()
                    .map(f -> List.of(f.code(), String.format(java.util.Locale.ROOT, "%.2f", f.weight()),
                            f.description())).toList());
        }
        if (preliminaryRisk != null) {
            md.heading(3, "Preliminary risk").field("Level", preliminaryRisk.level()).bullets(preliminaryRisk.factors());
        }
        return md.render();
    }
}
