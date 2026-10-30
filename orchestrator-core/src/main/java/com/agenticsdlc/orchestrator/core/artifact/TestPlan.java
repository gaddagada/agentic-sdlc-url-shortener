package com.agenticsdlc.orchestrator.core.artifact;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code test-design} stage: the test strategy and cases.
 *
 * @param strategy overall strategy
 * @param levels test levels used
 * @param cases test cases ({@code TC-n})
 * @param coverageMatrix requirement id to comma-separated test case ids; values are normalized to the
 *                       canonical {@code "TC-1, TC-4"} form (ids extracted with {@code TC-\d+}, prose dropped)
 * @param risksNotCovered requirements or risks without automated coverage
 */
public record TestPlan(String strategy, List<TestLevel> levels, List<TestCase> cases, Map<String, String> coverageMatrix,
                       List<String> risksNotCovered) implements ArtifactPayload {

    private static final Pattern CASE_ID = Pattern.compile("TC-\\d+");

    /**
     * Normalizes collections and text. Coverage matrix values are reduced to their test case ids so that prose a
     * model writes around the ids ({@code "TC-20 (automated checks); manual review in T14"}) is tolerated and the
     * stored value (and therefore the artifact hash) is canonical.
     */
    public TestPlan {
        strategy = Immutables.text(strategy);
        levels = Immutables.list(levels);
        cases = Immutables.list(cases);
        coverageMatrix = normalizeCoverage(coverageMatrix);
        risksNotCovered = Immutables.list(risksNotCovered);
    }

    /**
     * Extracts the test case ids ({@code TC-n}) referenced by a coverage matrix value, ignoring any surrounding
     * prose, in order of first appearance and without duplicates.
     *
     * @param value coverage matrix value
     * @return the referenced test case ids
     */
    public static List<String> caseIds(String value) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = CASE_ID.matcher(Immutables.text(value));
        while (m.find()) {
            ids.add(m.group());
        }
        return List.copyOf(ids);
    }

    private static Map<String, String> normalizeCoverage(Map<String, String> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        matrix.forEach((requirement, value) -> normalized.put(requirement, String.join(", ", caseIds(value))));
        return Collections.unmodifiableMap(normalized);
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
        coverageMatrix.forEach((requirement, value) -> {
            List<String> referenced = caseIds(value);
            p.require(!referenced.isEmpty() || listedAsNotCovered(requirement), "coverageMatrix entry " + requirement
                    + " references no test case (values must be test case ids such as \"TC-1, TC-4\"; list "
                    + "requirements without a case in risksNotCovered)");
            for (String caseId : referenced) {
                p.require(ids.contains(caseId),
                        "coverageMatrix entry " + requirement + " references unknown test case " + caseId);
            }
        });
        p.throwIfAny();
    }

    private boolean listedAsNotCovered(String requirement) {
        Pattern id = Pattern.compile("(?<![\\w-])" + Pattern.quote(requirement) + "(?![\\w-])");
        return risksNotCovered.stream().anyMatch(r -> id.matcher(r).find());
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
