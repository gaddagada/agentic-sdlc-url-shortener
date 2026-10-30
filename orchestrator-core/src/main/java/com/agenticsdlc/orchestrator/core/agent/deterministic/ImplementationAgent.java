package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.SandboxMaterializer;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.ContentKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.FileChange;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.Operation;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.ApiChange;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.Component;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.DataModelChange;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AcceptanceCriterion;
import com.agenticsdlc.orchestrator.core.artifact.TaskPlan;

/**
 * Deterministic implementation agent ({@code implement}): one SKELETON file per changed or new component
 * (package, class, constructor injection, endpoint and method stubs with {@code // TODO implement} markers),
 * a Flyway migration with real DDL per data model change, and one test skeleton per production file with test
 * methods named after the acceptance criteria. Plain request DTOs carry a documented test justification
 * instead of a dedicated test. Feedback from verification is reflected as {@code // Addressed feedback}
 * comments; requested missing tests are added. When sandboxes are enabled for REPO runs, the change set is
 * materialized into a sandbox and a {@code DELETE_SANDBOX} compensation is registered.
 */
public final class ImplementationAgent implements StageAgent {

    /** Source root for new components. */
    public static final String BASE_PATH = "url-shortener/src/main/java/com/agenticsdlc/shortener/";
    /** Migration directory. */
    public static final String MIGRATION_PATH = "url-shortener/src/main/resources/db/migration/";
    /** Build command of the change sets. */
    public static final String BUILD_COMMAND = "./mvnw -pl url-shortener verify";
    private static final Pattern MISSING_TESTS = Pattern.compile("Add tests for: (.+)");
    private static final Pattern CREATE_TABLE = Pattern.compile("create table (\\w+) \\(([^)]*)\\)");
    private static final Pattern ADD_COLUMN = Pattern.compile("add column (\\w+) to (\\w+)");

    /**
     * Creates the agent.
     */
    public ImplementationAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.IMPLEMENTATION;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        DesignSpec design = context.require(DesignSpec.class);
        RequirementSpec spec = context.require(RequirementSpec.class);
        ImpactAnalysis impact = context.find(ImpactAnalysis.class).orElse(null);
        TaskPlan plan = context.find(TaskPlan.class).orElse(null);
        ChangeSet changeSet = implement(design, spec, impact, plan, context.feedback());
        List<CompensationRequest> compensations = new ArrayList<>();
        SandboxMaterializer.materialize(changeSet, context).ifPresent(compensations::add);
        return StageResult.success(changeSet, changeSet.changes().size() + " files (" + changeSet.totalContentLines()
                + " lines) as skeletons" + (context.feedback().isEmpty() ? ""
                : "; addressed " + context.feedback().size() + " feedback items")
                + (compensations.isEmpty() ? "" : "; materialized into a sandbox"), compensations);
    }

    /**
     * Builds the change set.
     *
     * @param design design
     * @param spec requirement specification
     * @param impact impact analysis or null
     * @param plan task plan or null
     * @param feedback feedback entries
     * @return the change set
     */
    public ChangeSet implement(DesignSpec design, RequirementSpec spec, @Nullable ImpactAnalysis impact,
                               @Nullable TaskPlan plan, List<String> feedback) {
        Map<String, String> knownPaths = new LinkedHashMap<>();
        if (impact != null) {
            impact.components().forEach(c -> knownPaths.put(c.name(), c.path()));
        }
        List<Component> components = new ArrayList<>(design.components().stream()
                .filter(c -> !"TEST".equals(c.type())).toList());
        if (!design.apiChanges().isEmpty() && components.stream().noneMatch(c -> "CONTROLLER".equals(c.type()))) {
            components.add(new Component(ArchitectureAgent.controllerFor(design.apiChanges().getFirst().path()),
                    "Exposes the new endpoints", "CONTROLLER", true));
        }
        Map<String, List<ApiChange>> endpointsByController = assignEndpoints(components, design.apiChanges());
        Set<String> knownTypes = new LinkedHashSet<>(knownPaths.keySet());
        components.forEach(c -> knownTypes.add(c.name()));
        Set<String> requestedTests = requestedTests(feedback);
        List<String> implementTasks = plan == null ? List.of() : plan.tasks().stream()
                .filter(t -> t.type() == TaskPlan.TaskType.IMPLEMENT).map(TaskPlan.Task::id).toList();
        String firstTask = plan == null ? "" : plan.tasks().stream().filter(t -> t.type() == TaskPlan.TaskType.IMPLEMENT)
                .map(TaskPlan.Task::title).findFirst().orElse("");

        List<FileChange> production = new ArrayList<>();
        List<FileChange> tests = new ArrayList<>();
        List<String> dtoWithoutTests = new ArrayList<>();
        boolean feedbackNoted = false;
        for (Component component : components) {
            String path = !component.isNew() && knownPaths.containsKey(component.name())
                    && knownPaths.get(component.name()).endsWith(".java")
                    ? knownPaths.get(component.name()) : newPath(component);
            List<ApiChange> endpoints = endpointsByController.getOrDefault(component.name(), List.of());
            List<String> notes = feedbackNoted ? List.of() : feedback;
            feedbackNoted = true;
            String schema = design.apiChanges().stream().map(ApiChange::requestSchema)
                    .filter(s -> s.startsWith(component.name() + " ") || s.equals(component.name())).findFirst()
                    .orElse("");
            String content = JavaSkeletons.production(path, component, endpoints, firstTask, notes, knownTypes,
                    schema);
            String summary = component.responsibility() + (endpoints.isEmpty() ? ""
                    : ". Endpoints: " + String.join(", ", endpoints.stream().map(e -> e.method() + " " + e.path())
                    .toList()));
            production.add(new FileChange(path, component.isNew() ? Operation.CREATE : Operation.MODIFY, "java",
                    summary, ContentKind.SKELETON, content, implementTasks));
            boolean dto = "OTHER".equals(component.type()) && component.name().matches(".*(Request|Response)$");
            if (dto && !requestedTests.contains(component.name())) {
                dtoWithoutTests.add(component.name());
                continue;
            }
            String testPath = testPath(path, component.name());
            tests.add(new FileChange(testPath, Operation.CREATE, "java", "Tests for " + component.name()
                    + " derived from the acceptance criteria", ContentKind.SKELETON,
                    JavaSkeletons.test(testPath, component.name(), criteriaFor(component, spec)), implementTasks));
        }
        List<FileChange> migrations = migrations(design.dataModelChanges(), implementTasks);
        List<FileChange> all = new ArrayList<>(production);
        all.addAll(migrations);
        all.addAll(tests);

        List<String> risks = new ArrayList<>();
        risks.add("Skeleton code: method bodies marked TODO must be completed and reviewed before release");
        if (design.risk() != null) {
            design.risk().factors().forEach(f -> risks.add("Design risk: " + f));
        }
        if (!dtoWithoutTests.isEmpty()) {
            risks.add("Test justification: request DTOs " + String.join(", ", dtoWithoutTests)
                    + " carry no logic and are covered through the controller tests");
        }
        String rollback = "Revert the change commit and redeploy the previous version."
                + (migrations.isEmpty() ? "" : " Database: apply the documented reverse migration for "
                + String.join(", ", migrations.stream().map(FileChange::fileName).toList())
                + " before redeploying the previous version (Flyway undo is not automatic).");
        return new ChangeSet("HEAD", all, BUILD_COMMAND, risks, rollback, implementTasks);
    }

    private static Map<String, List<ApiChange>> assignEndpoints(List<Component> components, List<ApiChange> changes) {
        List<String> controllers = components.stream().filter(c -> "CONTROLLER".equals(c.type()))
                .map(Component::name).toList();
        Map<String, List<ApiChange>> byController = new LinkedHashMap<>();
        for (ApiChange change : changes) {
            String target = ArchitectureAgent.assignController(controllers, change.path());
            if (target != null) {
                byController.computeIfAbsent(target, k -> new ArrayList<>()).add(change);
            }
        }
        return byController;
    }

    private static Set<String> requestedTests(List<String> feedback) {
        Set<String> names = new LinkedHashSet<>();
        for (String entry : feedback) {
            Matcher m = MISSING_TESTS.matcher(entry);
            if (m.find()) {
                for (String name : m.group(1).split(",")) {
                    names.add(name.strip().replaceAll("[^A-Za-z0-9]", ""));
                }
            }
        }
        return names;
    }

    private static String newPath(Component component) {
        String layer = switch (component.type()) {
            case "CONTROLLER" -> "api";
            case "SERVICE" -> "service";
            case "REPOSITORY" -> "repository";
            case "ENTITY" -> "domain";
            case "CONFIG", "FILTER" -> "config";
            default -> component.name().matches(".*(Request|Response)$") ? "api/dto" : "service";
        };
        return BASE_PATH + layer + "/" + component.name() + ".java";
    }

    static String testPath(String productionPath, String name) {
        String testRoot = productionPath.replace("/src/main/java/", "/src/test/java/");
        int slash = testRoot.lastIndexOf('/');
        return testRoot.substring(0, slash + 1) + name + "Test.java";
    }

    private static List<AcceptanceCriterion> criteriaFor(Component component, RequirementSpec spec) {
        if ("CONTROLLER".equals(component.type()) || "SERVICE".equals(component.type())) {
            return spec.acceptanceCriteria();
        }
        return spec.acceptanceCriteria().stream().limit(2).toList();
    }

    private static List<FileChange> migrations(List<DataModelChange> changes, List<String> traces) {
        Map<String, List<String>> ddlByFile = new LinkedHashMap<>();
        int index = 1;
        for (DataModelChange change : changes) {
            String file = change.migration().isBlank() ? "V" + index + "__" + change.entity().toLowerCase(Locale.ROOT)
                    + ".sql" : change.migration();
            index++;
            ddlByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(ddl(change));
        }
        List<FileChange> files = new ArrayList<>();
        ddlByFile.forEach((file, statements) -> files.add(new FileChange(MIGRATION_PATH + file, Operation.CREATE, "sql",
                "Flyway migration: " + String.join("; ", changes.stream()
                        .filter(c -> file.equals(c.migration()) || c.migration().isBlank())
                        .map(DataModelChange::change).toList()),
                ContentKind.FULL, "-- Generated by the agentic SDLC orchestrator\n" + String.join("\n\n", statements)
                + "\n", traces)));
        return files;
    }

    /**
     * Derives DDL from a data model change ({@code create table t (cols)} or {@code add column c to t}).
     *
     * @param change the change
     * @return DDL statements
     */
    static String ddl(DataModelChange change) {
        Matcher create = CREATE_TABLE.matcher(change.change());
        if (create.find()) {
            List<String> columns = new ArrayList<>();
            for (String column : create.group(2).split(",")) {
                String name = column.strip();
                if (!name.isEmpty()) {
                    columns.add("    " + name + " " + columnType(name));
                }
            }
            return "CREATE TABLE " + create.group(1) + " (\n" + String.join(",\n", columns) + "\n);";
        }
        Matcher add = ADD_COLUMN.matcher(change.change());
        if (add.find()) {
            return "ALTER TABLE " + add.group(2) + " ADD COLUMN " + add.group(1) + " " + columnType(add.group(1))
                    .replace(" NOT NULL", "") + ";";
        }
        return "-- TODO: translate the data model change into DDL: " + change.change().replace("\n", " ");
    }

    private static String columnType(String column) {
        if (column.equals("id")) {
            return "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
        }
        if (column.endsWith("_id")) {
            return "BIGINT NOT NULL";
        }
        if (column.endsWith("_at")) {
            return column.startsWith("created") || column.startsWith("occurred") ? "TIMESTAMP NOT NULL"
                    : "TIMESTAMP NULL";
        }
        if (column.endsWith("_count")) {
            return "BIGINT NOT NULL DEFAULT 0";
        }
        if (column.startsWith("is_") || column.equals("custom_alias") || column.equals("enabled")) {
            return "BOOLEAN NOT NULL DEFAULT FALSE";
        }
        if (column.endsWith("url")) {
            return "VARCHAR(2048) NOT NULL";
        }
        if (column.equals("code")) {
            return "VARCHAR(32) NOT NULL UNIQUE";
        }
        if (column.equals("status")) {
            return "VARCHAR(16) NOT NULL";
        }
        if (column.endsWith("_hash")) {
            return "VARCHAR(64) NOT NULL";
        }
        return "VARCHAR(255) NULL";
    }
}
