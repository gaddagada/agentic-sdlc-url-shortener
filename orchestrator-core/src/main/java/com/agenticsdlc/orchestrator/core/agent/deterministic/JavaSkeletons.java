package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.ApiChange;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.Component;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AcceptanceCriterion;

/**
 * Generates deterministic Java skeletons for production types and tests.
 */
final class JavaSkeletons {

    private static final Pattern FIELD = Pattern.compile("(\\w+)\\??\\s*:\\s*(\\[)?");

    private JavaSkeletons() {
    }

    static String packageOf(String path) {
        String marker = path.contains("/src/test/java/") ? "/src/test/java/" : "/src/main/java/";
        int start = path.indexOf(marker);
        String relative = start >= 0 ? path.substring(start + marker.length()) : path;
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash).replace('/', '.');
    }

    static String production(String path, Component component, List<ApiChange> endpoints, String task,
                             List<String> feedback, Set<String> knownTypes, String requestSchema, boolean existing) {
        StringBuilder out = new StringBuilder();
        String pkg = packageOf(path);
        for (String entry : feedback) {
            out.append("// Addressed feedback: ").append(comment(entry)).append('\n');
        }
        if (existing) {
            out.append("// MODIFY skeleton for the existing type ").append(component.name())
                    .append(": merge the members below that are missing into ").append(path)
                    .append("; keep the existing members unchanged.\n");
        }
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        String name = component.name();
        String todo = "TODO implement: " + comment(task.isBlank() ? component.responsibility() : task);
        switch (component.type()) {
            case "CONTROLLER" -> controller(out, name, component, endpoints, todo, knownTypes);
            case "SERVICE" -> service(out, name, component, todo, knownTypes);
            case "REPOSITORY" -> repository(out, name, component, knownTypes);
            case "ENTITY" -> entity(out, name, component);
            case "CONFIG" -> simple(out, name, component, "org.springframework.context.annotation.Configuration",
                    "@Configuration", todo);
            case "FILTER" -> simple(out, name, component, "org.springframework.stereotype.Component", "@Component",
                    todo + " (filter behaviour)");
            default -> {
                if (name.matches(".*(Request|Response)$")) {
                    dto(out, name, component, requestSchema);
                } else {
                    simple(out, name, component, "org.springframework.stereotype.Component", "@Component", todo);
                }
            }
        }
        return out.toString();
    }

    private static void javadoc(StringBuilder out, Component component) {
        out.append("/**\n * ").append(comment(component.responsibility()))
                .append("\n *\n * <p>Generated skeleton: complete the TODO markers.</p>\n */\n");
    }

    private static void controller(StringBuilder out, String name, Component component, List<ApiChange> endpoints,
                                   String todo, Set<String> knownTypes) {
        List<String> imports = new ArrayList<>(List.of("org.springframework.web.bind.annotation.RestController"));
        for (ApiChange e : endpoints) {
            String annotation = mappingAnnotation(e.method());
            String fqcn = "org.springframework.web.bind.annotation." + annotation;
            if (!imports.contains(fqcn)) {
                imports.add(fqcn);
            }
            if (!e.method().equals("GET") && !e.method().equals("DELETE")
                    && !imports.contains("org.springframework.web.bind.annotation.RequestBody")) {
                imports.add("org.springframework.web.bind.annotation.RequestBody");
            }
            if (e.path().contains("{") && !imports.contains("org.springframework.web.bind.annotation.PathVariable")) {
                imports.add("org.springframework.web.bind.annotation.PathVariable");
            }
        }
        imports.stream().sorted().forEach(i -> out.append("import ").append(i).append(";\n"));
        out.append('\n');
        javadoc(out, component);
        out.append("@RestController\npublic class ").append(name).append(" {\n");
        String service = serviceFor(name);
        if (knownTypes.contains(service)) {
            String field = Text.decapitalize(service);
            out.append("\n    private final ").append(service).append(' ').append(field).append(";\n\n");
            out.append("    public ").append(name).append('(').append(service).append(' ').append(field)
                    .append(") {\n        this.").append(field).append(" = ").append(field).append(";\n    }\n");
        }
        for (ApiChange e : endpoints) {
            out.append("\n    /**\n     * ").append(comment(e.summary())).append(" (").append(e.method()).append(' ')
                    .append(comment(e.path())).append(").\n     */\n");
            out.append("    @").append(mappingAnnotation(e.method())).append("(\"").append(e.path()).append("\")\n");
            out.append("    public Object ").append(handlerName(e)).append('(');
            List<String> params = new ArrayList<>();
            Matcher vars = Pattern.compile("\\{(\\w+)}").matcher(e.path());
            while (vars.find()) {
                params.add("@PathVariable String " + vars.group(1));
            }
            if (!e.method().equals("GET") && !e.method().equals("DELETE")) {
                params.add("@RequestBody Object request");
            }
            out.append(String.join(", ", params)).append(") {\n");
            out.append("        // ").append(todo).append('\n');
            out.append("        throw new UnsupportedOperationException(\"TODO: ").append(e.method()).append(' ')
                    .append(e.path().replace("\"", "")).append("\");\n    }\n");
        }
        if (endpoints.isEmpty()) {
            out.append("\n    // ").append(todo).append('\n');
        }
        out.append("}\n");
    }

    private static void service(StringBuilder out, String name, Component component, String todo,
                                Set<String> knownTypes) {
        out.append("import org.springframework.stereotype.Service;\n\n");
        javadoc(out, component);
        out.append("@Service\npublic class ").append(name).append(" {\n");
        String repository = name.replaceAll("Service$", "Repository");
        if (!repository.equals(name) && knownTypes.contains(repository)) {
            String field = Text.decapitalize(repository);
            out.append("\n    private final ").append(repository).append(' ').append(field).append(";\n\n");
            out.append("    public ").append(name).append('(').append(repository).append(' ').append(field)
                    .append(") {\n        this.").append(field).append(" = ").append(field).append(";\n    }\n");
        }
        out.append("\n    /**\n     * Applies the business rules of this change.\n     */\n");
        out.append("    public void apply() {\n        // ").append(todo)
                .append("\n        throw new UnsupportedOperationException(\"TODO\");\n    }\n}\n");
    }

    private static void repository(StringBuilder out, String name, Component component, Set<String> knownTypes) {
        String entity = name.replaceAll("Repository$", "");
        if (knownTypes.contains(entity)) {
            out.append("import org.springframework.data.jpa.repository.JpaRepository;\n\n");
            javadoc(out, component);
            out.append("public interface ").append(name).append(" extends JpaRepository<").append(entity)
                    .append(", Long> {\n    // TODO implement: query methods\n}\n");
        } else {
            javadoc(out, component);
            out.append("public interface ").append(name).append(" {\n    // TODO implement: persistence queries\n}\n");
        }
    }

    private static void entity(StringBuilder out, String name, Component component) {
        out.append("import jakarta.persistence.Entity;\nimport jakarta.persistence.GeneratedValue;\n")
                .append("import jakarta.persistence.GenerationType;\nimport jakarta.persistence.Id;\n")
                .append("import jakarta.persistence.Table;\n\n");
        javadoc(out, component);
        out.append("@Entity\n@Table(name = \"").append(tableName(name)).append("\")\npublic class ").append(name)
                .append(" {\n\n    @Id\n    @GeneratedValue(strategy = GenerationType.IDENTITY)\n    private Long id;\n\n")
                .append("    protected ").append(name).append("() {\n        // required by JPA\n    }\n\n")
                .append("    // TODO implement: persistent fields and invariants\n}\n");
    }

    private static void dto(StringBuilder out, String name, Component component, String schema) {
        List<String> fields = new ArrayList<>();
        int brace = schema.indexOf('{');
        if (brace >= 0) {
            Matcher m = FIELD.matcher(schema.substring(brace));
            while (m.find()) {
                fields.add((m.group(2) != null ? "java.util.List<Object> " : "Object ") + m.group(1));
            }
        }
        if (fields.isEmpty()) {
            fields.add("Object payload");
        }
        javadoc(out, component);
        out.append("public record ").append(name).append('(').append(String.join(", ", fields)).append(") {\n")
                .append("    // TODO implement: validation constraints\n}\n");
    }

    private static void simple(StringBuilder out, String name, Component component, String importName,
                               String annotation, String todo) {
        out.append("import ").append(importName).append(";\n\n");
        javadoc(out, component);
        out.append(annotation).append("\npublic class ").append(name).append(" {\n    // ").append(todo)
                .append("\n}\n");
    }

    /**
     * Generates a new test class skeleton. Every test method fails with {@code Assertions.fail("TODO: ...")} until it
     * is implemented, so skeleton tests can never pass vacuously.
     */
    static String test(String path, String name, List<AcceptanceCriterion> criteria) {
        StringBuilder out = new StringBuilder();
        String pkg = packageOf(path);
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        out.append("import org.junit.jupiter.api.Test;\n\n");
        out.append("/**\n * Tests for ").append(name).append(" derived from the acceptance criteria.\n *\n")
                .append(" * <p>Generated skeleton: every test fails until it is implemented.</p>\n */\n");
        out.append("class ").append(name).append("Test {\n");
        testMethods(out, criteria);
        out.append("}\n");
        return out.toString();
    }

    /**
     * Generates the additions to an existing test class (a MODIFY skeleton): what to add, not a replacement of the
     * file. The added methods fail until they are implemented.
     */
    static String testAdditions(String path, String name, List<AcceptanceCriterion> criteria) {
        StringBuilder out = new StringBuilder();
        out.append("// MODIFY skeleton for the existing test class ").append(name).append("Test: add the test methods ")
                .append("below to ").append(path).append(" and keep the existing tests unchanged.\n")
                .append("// Required import: org.junit.jupiter.api.Test. Every added test fails until it is ")
                .append("implemented.\n");
        testMethods(out, criteria);
        return out.toString();
    }

    private static void testMethods(StringBuilder out, List<AcceptanceCriterion> criteria) {
        if (criteria.isEmpty()) {
            out.append("\n    @Test\n    void behavesAsDesigned() {\n")
                    .append("        org.junit.jupiter.api.Assertions.fail(\"TODO: implement behavesAsDesigned\");\n")
                    .append("    }\n");
        }
        for (AcceptanceCriterion ac : criteria) {
            String method = ac.id().toLowerCase(Locale.ROOT).replace("-", "") + "_" + Text.camelCase(ac.then(), 5);
            out.append("\n    @Test\n    void ").append(method).append("() {\n")
                    .append("        // Given ").append(comment(ac.given())).append('\n')
                    .append("        // When ").append(comment(ac.when())).append('\n')
                    .append("        // Then ").append(comment(ac.then())).append('\n')
                    .append("        org.junit.jupiter.api.Assertions.fail(\"TODO: implement ").append(method)
                    .append(" (traces ").append(String.join(", ", ac.traces()).replace("\"", "")).append(")\");\n")
                    .append("    }\n");
        }
    }

    static String handlerName(ApiChange change) {
        String verb = switch (change.method()) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> "get";
        };
        StringBuilder name = new StringBuilder(verb);
        for (String segment : change.path().split("/")) {
            if (!segment.isBlank() && !segment.equals("api") && !segment.matches("v\\d+") && !segment.startsWith("{")) {
                name.append(Text.capitalize(segment.replaceAll("[^A-Za-z0-9]", "")));
            }
        }
        if (name.toString().equals("get") && change.path().startsWith("/{")) {
            return "redirect";
        }
        return name.toString();
    }

    private static String mappingAnnotation(String method) {
        return switch (method) {
            case "POST" -> "PostMapping";
            case "PUT" -> "PutMapping";
            case "PATCH" -> "PatchMapping";
            case "DELETE" -> "DeleteMapping";
            default -> "GetMapping";
        };
    }

    private static String serviceFor(String controller) {
        if (controller.equals("RedirectController")) {
            return "LinkService";
        }
        return controller.replaceAll("Controller$", "Service");
    }

    private static String tableName(String entity) {
        String snake = entity.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
        return snake.endsWith("s") ? snake : snake + "s";
    }

    private static String comment(String text) {
        return Text.normalizeWhitespace(text).replace("*/", "* /");
    }
}
