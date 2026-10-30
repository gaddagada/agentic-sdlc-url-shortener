package com.agenticsdlc.orchestrator.core.artifact;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code test-design} stage: the test strategy and cases.
 *
 * @param strategy overall strategy
 * @param levels test levels used
 * @param cases test cases ({@code TC-n})
 * @param coverageMatrix requirement id to comma-separated test case ids
 * @param risksNotCovered requirements or risks without automated coverage
 */
public record TestPlan(String strategy, List<TestLevel> levels, List<TestCase> cases, Map<String, String> coverageMatrix,
                       List<String> risksNotCovered) implements ArtifactPayload {

    /**
     * Normalizes collections and text.
     */
    public TestPlan {
        strategy = Immutables.text(strategy);
        levels = Immutables.list(levels);
        cases = Immutables.list(cases);
        coverageMatrix = Immutables.map(coverageMatrix);
        risksNotCovered = Immutables.list(risksNotCovered);
    }

    /** Test level. */
    public enum Level {
        /** Unit tests. */
        UNIT,
        /** Framework slice tests. */
        SLICE,
        /** Integration tests. */
        INTEGRATION,
        /** API contract tests. */
        CONTRACT,
        /** Performance tests. */
        PERFORMANCE,
        /** Security tests. */
        SECURITY
    }

    /** Test case type. */
    public enum CaseType {
        /** Happy path. */
        POSITIVE,
        /** Invalid input or forbidden action. */
        NEGATIVE,
        /** Limits. */
        BOUNDARY,
        /** Failure and recovery. */
        RECOVERY,
        /** Security behaviour. */
        SECURITY
    }

    /**
     * A test level with its scope and tooling.
     *
     * @param level level
     * @param scope what is tested at this level
     * @param tooling tools used
     */
    public record TestLevel(Level level, String scope, String tooling) {
        /**
         * Normalizes text.
         */
        public TestLevel {
            scope = Immutables.text(scope);
            tooling = Immutables.text(tooling);
        }
    }

    /**
     * A test case.
     *
     * @param id {@code TC-n}
     * @param title title
     * @param level level
     * @param type type
     * @param preconditions preconditions
     * @param steps steps
     * @param expected expected result
     * @param traces requirement and acceptance criterion ids
     */
    public record TestCase(String id, String title, Level level, CaseType type, String preconditions,
                           List<String> steps, String expected, List<String> traces) {
        /**
         * Normalizes fields.
         */
        public TestCase {
            id = Immutables.text(id);
            title = Immutables.text(title);
            preconditions = Immutables.text(preconditions);
            steps = Immutables.list(steps);
            expected = Immutables.text(expected);
            traces = Immutables.list(traces);
        }
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.TEST_PLAN;
    }

    @Override
    public void validate() {
        Problems p = new Problems("TestPlan");
        p.requireText(strategy, "strategy");
        p.requireNonEmpty(levels, "levels");
        p.requireNonEmpty(cases, "cases");
        p.requireUnique(cases, TestCase::id, "cases");
        Set<String> ids = new HashSet<>();
        for (TestCase c : cases) {
            p.requirePattern(c.id(), "TC-\\d+", "test case id");
            p.requireText(c.title(), "test case " + c.id() + " title");
            p.requireNonNull(c.level(), "test case " + c.id() + " level");
            p.requireNonNull(c.type(), "test case " + c.id() + " type");
            ids.add(c.id());
        }
        coverageMatrix.forEach((requirement, caseIds) -> {
            for (String caseId : caseIds.split(",")) {
                String trimmed = caseId.strip();
                p.require(trimmed.isEmpty() || ids.contains(trimmed),
                        "coverageMatrix entry " + requirement + " references unknown test case " + trimmed);
            }
        });
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Test plan").paragraph(strategy);
        md.heading(3, "Levels").table(List.of("Level", "Scope", "Tooling"),
                levels.stream().map(l -> List.of(String.valueOf(l.level()), l.scope(), l.tooling())).toList());
        md.heading(3, "Cases").table(List.of("ID", "Level", "Type", "Title", "Expected", "Traces"),
                cases.stream().map(c -> List.of(c.id(), String.valueOf(c.level()), String.valueOf(c.type()), c.title(),
                        c.expected(), Markdown.join(c.traces()))).toList());
        md.heading(3, "Coverage matrix").table(List.of("Requirement", "Test cases"),
                coverageMatrix.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList());
        md.heading(3, "Risks not covered").bullets(risksNotCovered);
        return md.render();
    }
}
