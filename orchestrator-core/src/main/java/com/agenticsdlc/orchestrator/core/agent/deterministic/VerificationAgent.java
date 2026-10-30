package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.ContentKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.FileChange;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.Operation;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.TestPlan;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport.BuildOutcome;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport.Traceability;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;
import com.agenticsdlc.orchestrator.core.workspace.BuildResult;
import com.agenticsdlc.orchestrator.core.workspace.BuildRunner;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

/**
 * Deterministic verifier ({@code verify}): structural checks over the change set against the design and the
 * test plan and, when enabled and a sandbox exists, a real build. Every failed check produces imperative feedback
 * for the implementation stage.
 *
 * <p>Checks, in order:</p>
 * <ul>
 *   <li>{@code paths-within-allowed-roots}: every path lies under {@link #ALLOWED_ROOTS} (the workload);</li>
 *   <li>{@code no-secrets}: no credential patterns in paths or contents;</li>
 *   <li>{@code no-create-over-existing-file} (only when a codebase index is available): no CREATE of an indexed
 *       file;</li>
 *   <li>{@code tests-accompany-production-changes}: a test or a documented justification per production file;</li>
 *   <li>{@code tests-are-not-vacuous}: every test file ({@code *Test}, {@code *Tests}, {@code *IT},
 *       {@code *Spec}) contains an assertion outside comments ({@code assert}, {@code Assertions.}, {@code fail(},
 *       {@code expect} or {@code verify(}, case-insensitive); skeleton tests failing with
 *       {@code fail("TODO ...")} pass by design because they cannot pass vacuously;</li>
 *   <li>{@code migration-present-when-schema-changes};</li>
 *   <li>{@code api-changes-implemented} for complete code: every designed endpoint appears in a request mapping
 *       annotation ({@code @PostMapping("/bulk")} under a class-level {@code @RequestMapping("/api/v1/links")},
 *       {@code @RequestMapping(value = ..., method = RequestMethod.POST)}, ...) of FULL or DIFF content, with a
 *       matching HTTP method; or {@code api-changes-planned} when the change set contains SKELETON content (passed
 *       when a skeleton or summary mentions the endpoint path);</li>
 *   <li>{@code content-present} and {@code traceability}.</li>
 * </ul>
 *
 * <p>The report's {@code skeletonOnly} flag is set when the change set contains SKELETON content, and the note
 * {@value VerificationReport#NO_BUILD_NOTE} is added when no build ran. Sandbox builds always run the
 * engine-configured command ({@link com.agenticsdlc.orchestrator.core.agent.AgentSettings#buildCommand()}); the
 * change set's {@code buildCommand} is informational only.</p>
 */
public final class VerificationAgent implements StageAgent {

    /** Allowed path roots of a change set: the workload only ({@link WorkspaceSandbox#ALLOWED_ROOTS}). */
    public static final List<String> ALLOWED_ROOTS = WorkspaceSandbox.ALLOWED_ROOTS;

    /** Note added to reports of skeleton change sets. */
    public static final String SKELETON_NOTE = "skeleton change plan: endpoints are planned, not implemented; "
            + "complete and build before merge";

    private static final Pattern ASSERTION = Pattern.compile("assert|Assertions\\.|fail\\(|expect|verify\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_FILE = Pattern.compile(".*(Test|Tests|IT|Spec)\\.(java|kt|groovy)$");
    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete|Request)Mapping\\b(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern PATH_ATTRIBUTE = Pattern.compile("\\b(?:value|path)\\s*=\\s*(\\{[^}]*}|\"[^\"]*\")");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE)");
    private static final Pattern TYPE_DECLARATION = Pattern.compile("\\b(class|interface|record)\\s+\\w+");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}");

    private final @Nullable BuildRunner buildRunner;

    /**
     * Creates the agent without a build runner (structural verification only).
     */
    public VerificationAgent() {
        this(null);
    }

    /**
     * Creates the agent.
     *
     * @param buildRunner runner for sandbox builds, or null
     */
    public VerificationAgent(@Nullable BuildRunner buildRunner) {
        this.buildRunner = buildRunner;
    }

    @Override
    public AgentRole role() {
        return AgentRole.VERIFICATION;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        ChangeSet changeSet = context.require(ChangeSet.class);
        DesignSpec design = context.require(DesignSpec.class);
        TestPlan testPlan = context.require(TestPlan.class);
        RequirementSpec spec = context.require(RequirementSpec.class);
        BuildOutcome build = null;
        if (context.settings().verifyBuildEnabled() && buildRunner != null) {
            Path sandbox = WorkspaceSandbox.pathFor(context.settings().sandboxRoot(), context.runId());
            if (Files.isDirectory(sandbox)) {
                // the engine-configured command; the agent-written changeSet.buildCommand() is never executed
                build = build(sandbox, context.settings().buildCommand(), context);
            }
        }
        VerificationReport report = verify(changeSet, design, testPlan, spec, build, context.workspaceIndex());
        long failed = report.structuralChecks().stream().filter(c -> !c.passed()).count();
        return StageResult.success(report, (report.passed() ? "all " + report.structuralChecks().size()
                + " checks passed" : failed + " checks failed; " + report.feedbackForImplementation().size()
                + " feedback items for implementation") + (report.notes().isEmpty() ? ""
                : " (" + String.join("; ", report.notes()) + ")"));
    }

    private BuildOutcome build(Path sandbox, String command, StageContext context) throws StageFailure {
        try {
            BuildResult result = buildRunner.run(sandbox, command, context.settings().buildTimeout());
            return new BuildOutcome(result.executed(), result.command(), result.exitCode(), result.durationMs(),
                    result.summary(), result.failures());
        } catch (IllegalArgumentException e) {
            return new BuildOutcome(false, command, -1, 0L, "build not allowed: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw StageFailure.transientFailure("build interrupted");
        }
    }

    /**
     * Runs the structural checks without a codebase index.
     *
     * @param changeSet change set
     * @param design design
     * @param testPlan test plan
     * @param spec requirement specification
     * @param build build outcome or null
     * @return the report
     */
    public VerificationReport verify(ChangeSet changeSet, DesignSpec design, TestPlan testPlan, RequirementSpec spec,
                                     @Nullable BuildOutcome build) {
        return verify(changeSet, design, testPlan, spec, build, null);
    }

    /**
     * Runs the structural checks.
     *
     * @param changeSet change set
     * @param design design
     * @param testPlan test plan
     * @param spec requirement specification
     * @param build build outcome or null
     * @param index codebase index of the workspace, or null (then {@code no-create-over-existing-file} is skipped)
     * @return the report
     */
    public VerificationReport verify(ChangeSet changeSet, DesignSpec design, TestPlan testPlan, RequirementSpec spec,
                                     @Nullable BuildOutcome build, @Nullable CodebaseIndex index) {
        List<Check> checks = new ArrayList<>();
        List<String> feedback = new ArrayList<>();

        List<String> outside = changeSet.changes().stream().map(FileChange::path)
                .filter(p -> !WorkspaceSandbox.isAllowedPath(p)).toList();
        checks.add(outside.isEmpty() ? Check.pass("paths-within-allowed-roots", "all "
                + changeSet.changes().size() + " paths are under " + String.join(", ", ALLOWED_ROOTS))
                : Check.fail("paths-within-allowed-roots", "outside allowed roots: " + String.join(", ", outside)));
        if (!outside.isEmpty()) {
            feedback.add("Move these files under an allowed root (" + String.join(", ", ALLOWED_ROOTS) + "): "
                    + String.join(", ", outside));
        }

        List<String> secretFiles = changeSet.changes().stream()
                .filter(c -> SecretPatterns.containsSecret(c.content()) || SecretPatterns.containsSecret(c.path()))
                .map(FileChange::path).toList();
        checks.add(secretFiles.isEmpty() ? Check.pass("no-secrets", "no credential patterns found")
                : Check.fail("no-secrets", "credential patterns found in: " + String.join(", ", secretFiles)));
        if (!secretFiles.isEmpty()) {
            feedback.add("Remove credentials from: " + String.join(", ", secretFiles)
                    + " (read them from configuration or a secret store)");
        }

        if (index != null && !index.isEmpty()) {
            List<String> overwritten = changeSet.changes().stream()
                    .filter(c -> c.operation() == Operation.CREATE && index.containsPath(c.path()))
                    .map(FileChange::path).toList();
            checks.add(overwritten.isEmpty() ? Check.pass("no-create-over-existing-file", "no CREATE targets one of "
                    + "the " + index.indexedFiles() + " indexed files")
                    : Check.fail("no-create-over-existing-file", "CREATE of existing files: "
                    + String.join(", ", overwritten)));
            if (!overwritten.isEmpty()) {
                feedback.add("Use MODIFY (preferably with contentKind DIFF) instead of CREATE for these existing "
                        + "files: " + String.join(", ", overwritten));
            }
        }

        String risks = String.join(" ", changeSet.risks()).toLowerCase(Locale.ROOT);
        List<String> untested = new ArrayList<>();
        for (FileChange change : changeSet.changes()) {
            if (change.operation() == Operation.DELETE || !change.path().endsWith(".java")
                    || !change.path().contains("src/main/")) {
                continue;
            }
            String type = change.fileName().replace(".java", "");
            boolean tested = changeSet.changes().stream().anyMatch(t -> t.path().contains("src/test/")
                    && t.fileName().contains(type));
            boolean justified = risks.contains("test justification") && risks.contains(type.toLowerCase(Locale.ROOT));
            if (!tested && !justified) {
                untested.add(type);
            }
        }
        checks.add(untested.isEmpty() ? Check.pass("tests-accompany-production-changes",
                "every production change has a test or a documented justification")
                : Check.fail("tests-accompany-production-changes", "no test for: " + String.join(", ", untested)));
        if (!untested.isEmpty()) {
            feedback.add("Add tests for: " + String.join(", ", untested));
        }

        List<FileChange> testFiles = changeSet.changes().stream()
                .filter(c -> c.operation() != Operation.DELETE && c.path().contains("src/test/")
                        && TEST_FILE.matcher(c.fileName()).matches() && !c.content().isBlank())
                .toList();
        List<String> vacuous = testFiles.stream().filter(c -> !ASSERTION.matcher(code(c)).find())
                .map(FileChange::path).toList();
        checks.add(vacuous.isEmpty() ? Check.pass("tests-are-not-vacuous", testFiles.isEmpty() ? "no test files"
                : "all " + testFiles.size() + " test files assert or fail explicitly")
                : Check.fail("tests-are-not-vacuous", "no assertion in: " + String.join(", ", vacuous)));
        if (!vacuous.isEmpty()) {
            feedback.add("Add real assertions (or Assertions.fail(\"TODO ...\") for unfinished tests) to: "
                    + String.join(", ", vacuous));
        }

        boolean schemaChange = !design.dataModelChanges().isEmpty();
        boolean migration = changeSet.changes().stream().anyMatch(c -> c.path().contains("db/migration/")
                && c.path().endsWith(".sql"));
        checks.add(!schemaChange || migration ? Check.pass("migration-present-when-schema-changes",
                schemaChange ? "migration present for the data model changes" : "no data model change")
                : Check.fail("migration-present-when-schema-changes", "design changes the data model but the change "
                + "set has no Flyway migration"));
        if (schemaChange && !migration) {
            feedback.add("Add a Flyway migration under src/main/resources/db/migration for the data model changes");
        }

        boolean skeletonOnly = changeSet.changes().stream()
                .anyMatch(c -> c.operation() != Operation.DELETE && c.contentKind() == ContentKind.SKELETON);
        apiCheck(changeSet, design, skeletonOnly, checks, feedback);

        List<String> empty = changeSet.changes().stream()
                .filter(c -> c.operation() != Operation.DELETE
                        && (c.contentKind() == ContentKind.NONE || c.content().isBlank()))
                .map(FileChange::path).toList();
        checks.add(empty.isEmpty() ? Check.pass("content-present", "every created or modified file has content")
                : Check.fail("content-present", "no content for: " + String.join(", ", empty)));
        if (!empty.isEmpty()) {
            feedback.add("Provide content for: " + String.join(", ", empty));
        }

        List<String> uncovered = new ArrayList<>();
        for (RequirementSpec.Requirement fr : spec.functional()) {
            boolean traced = testPlan.cases().stream().anyMatch(c -> c.traces().contains(fr.id()));
            if (!traced) {
                uncovered.add(fr.id());
            }
        }
        Traceability traceability = new Traceability(spec.functional().size(),
                spec.functional().size() - uncovered.size(), uncovered);
        checks.add(uncovered.isEmpty() ? Check.pass("traceability", "every functional requirement is traced by "
                + "at least one test case") : Check.fail("traceability", "untraced: " + String.join(", ", uncovered)));
        if (!uncovered.isEmpty()) {
            feedback.add("Add test cases tracing: " + String.join(", ", uncovered));
        }
        if (build != null && build.exitCode() != 0) {
            feedback.add("Fix the build: " + build.summary());
        }
        List<String> notes = new ArrayList<>();
        if (build == null || !build.executed()) {
            notes.add(VerificationReport.NO_BUILD_NOTE);
        }
        if (skeletonOnly) {
            notes.add(SKELETON_NOTE);
        }
        boolean passed = VerificationReport.computePassed(checks, traceability, build);
        return new VerificationReport(checks, traceability, build, passed, feedback, skeletonOnly, notes);
    }

    private static void apiCheck(ChangeSet changeSet, DesignSpec design, boolean skeletonOnly, List<Check> checks,
                                 List<String> feedback) {
        List<FileChange> code = changeSet.changes().stream()
                .filter(c -> c.operation() != Operation.DELETE && c.path().contains("src/main/")
                        && (c.contentKind() == ContentKind.FULL || c.contentKind() == ContentKind.DIFF))
                .toList();
        List<FileChange> skeletons = changeSet.changes().stream()
                .filter(c -> c.operation() != Operation.DELETE && c.path().contains("src/main/")
                        && c.contentKind() == ContentKind.SKELETON)
                .toList();
        Set<String> missing = new LinkedHashSet<>();
        for (DesignSpec.ApiChange api : design.apiChanges()) {
            boolean implemented = code.stream().anyMatch(c -> implementsEndpoint(c, api.method(), api.path()));
            boolean planned = implemented || skeletons.stream().anyMatch(c -> c.summary().contains(api.path())
                    || c.content().contains(api.path()));
            if (skeletonOnly ? !planned : !implemented) {
                missing.add(api.method() + " " + api.path());
            }
        }
        String name = skeletonOnly ? "api-changes-planned" : "api-changes-implemented";
        if (missing.isEmpty()) {
            checks.add(Check.pass(name, skeletonOnly ? design.apiChanges().size() + " API changes planned in the "
                    + "skeleton change plan (not implemented)" : design.apiChanges().size()
                    + " API changes implemented by request mappings"));
        } else {
            checks.add(Check.fail(name, (skeletonOnly ? "not planned: " : "not implemented: ")
                    + String.join(", ", missing)));
            feedback.add("Implement these endpoints in a controller" + (skeletonOnly ? "" : " (request mapping "
                    + "annotation with the full path and HTTP method)") + ": " + String.join(", ", missing));
        }
    }

    /**
     * Returns whether a FULL or DIFF change maps the endpoint: a request mapping annotation whose path, combined
     * with a class-level {@code @RequestMapping} prefix, equals the endpoint path (path variable names are
     * ignored) and whose HTTP method matches. For a DIFF without a class-level mapping in its hunks, a method-level
     * path that is a non-empty suffix of the endpoint path also counts (the class prefix lies outside the hunk).
     *
     * @param change the change
     * @param method HTTP method of the endpoint
     * @param path endpoint path
     * @return true when implemented
     */
    static boolean implementsEndpoint(FileChange change, String method, String path) {
        String content = code(change);
        String expected = normalizeEndpoint(path);
        List<String> prefixes = new ArrayList<>();
        List<String[]> methodMappings = new ArrayList<>();
        Matcher m = MAPPING.matcher(content);
        while (m.find()) {
            String kind = m.group(1);
            String args = m.group(2) == null ? "" : m.group(2);
            List<String> paths = mappingPaths(args);
            int next = content.indexOf('{', m.end());
            String declaration = content.substring(m.end(), next < 0 ? content.length() : next);
            if ("Request".equals(kind) && TYPE_DECLARATION.matcher(declaration).find()) {
                prefixes.addAll(paths);
                continue;
            }
            String httpMethod = "Request".equals(kind) ? requestMethod(args) : kind.toUpperCase(Locale.ROOT);
            for (String p : paths) {
                methodMappings.add(new String[] {httpMethod, p});
            }
        }
        boolean diffWithoutPrefix = change.contentKind() == ContentKind.DIFF && prefixes.isEmpty();
        if (prefixes.isEmpty()) {
            prefixes.add("");
        }
        for (String[] mapping : methodMappings) {
            if (!mapping[0].isEmpty() && !mapping[0].equalsIgnoreCase(method)) {
                continue;
            }
            for (String prefix : prefixes) {
                if (normalizeEndpoint(prefix + "/" + mapping[1]).equals(expected)) {
                    return true;
                }
            }
            String suffix = normalizeEndpoint(mapping[1]);
            if (diffWithoutPrefix && !suffix.equals("/") && expected.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> mappingPaths(String args) {
        String a = args.strip();
        String source;
        Matcher attribute = PATH_ATTRIBUTE.matcher(a);
        if (attribute.find()) {
            source = attribute.group(1);
        } else if (a.startsWith("\"")) {
            int end = a.indexOf('"', 1);
            source = end < 0 ? "" : a.substring(0, end + 1);
        } else if (a.startsWith("{")) {
            int end = a.indexOf('}');
            source = end < 0 ? "" : a.substring(0, end + 1);
        } else {
            source = "";
        }
        List<String> paths = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(source);
        while (quoted.find()) {
            paths.add(quoted.group(1));
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static String requestMethod(String args) {
        Matcher m = REQUEST_METHOD.matcher(args);
        return m.find() ? m.group(1) : "";
    }

    private static String normalizeEndpoint(String path) {
        String p = PATH_VARIABLE.matcher("/" + path.strip()).replaceAll("{}");
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * Returns the code to inspect: for a DIFF the added and context lines (without diff markers, headers and
     * removed lines), otherwise the content; full-line comments are dropped in both cases, so a commented-out
     * mapping or an {@code // assert ...} remark does not count.
     */
    private static String code(FileChange change) {
        StringBuilder out = new StringBuilder();
        boolean diff = change.contentKind() == ContentKind.DIFF;
        for (String raw : change.content().split("\n", -1)) {
            String line = raw;
            if (diff) {
                if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") || line.startsWith("-")
                        || line.startsWith("diff ") || line.startsWith("index ")) {
                    continue;
                }
                line = line.startsWith("+") || line.startsWith(" ") ? line.substring(1) : line;
            }
            String stripped = line.strip();
            if (stripped.startsWith("//") || stripped.startsWith("/*") || stripped.startsWith("*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
