package com.agenticsdlc.orchestrator.core.policy.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads and validates {@link PolicyPack}s. Loading is strict and reports every problem at once, each with its JSON
 * path ({@link PolicyPackException#problems()}):
 *
 * <ul>
 *   <li>structure: malformed JSON, duplicate keys, unknown fields, missing required fields, wrong JSON types, unknown
 *       enum values ({@link GatePhase}, {@link Effect}, {@link Severity}, risk levels, release decisions, autonomy
 *       levels; matched case-insensitively), unknown condition types, conditions nested deeper than
 *       {@value #MAX_CONDITION_DEPTH} levels;</li>
 *   <li>content: rule ids that do not match {@link DeclarativeRule#ID_PATTERN}, duplicate rule ids, rule ids of
 *       built-in rules, invalid regexes, regexes longer than 1000 characters or with a
 *       nested quantifier such as {@code (a+)+} (catastrophic backtracking; at evaluation every regex search also
 *       has a time budget, see {@link DeclarativePolicyRule}), invalid globs (blank, backslashes, absolute,
 *       {@code ..} segments), empty
 *       regex/glob/condition lists, negative size limits, blank messages, stages on RUN_START rules,
 *       {@code payloadMatches} outside EXIT rules (only exit gates have a candidate payload), WARN rules with a
 *       severity other than LOW;</li>
 *   <li>governance: disabling an unknown built-in rule, or one of {@link PolicyPack#NON_DISABLEABLE_RULE_IDS}.</li>
 * </ul>
 *
 * <p>The core reads JSON only. YAML is a superset of JSON: the application parses a YAML pack into a tree and passes
 * its JSON form to {@link #fromJson(String)}.</p>
 */
public final class PolicyPackLoader {

    /** Classpath location of the sample pack shipped with the core. */
    public static final String SAMPLE_RESOURCE = "/policy/sample-policy-pack.json";

    /** Maximum nesting depth of conditions. */
    public static final int MAX_CONDITION_DEPTH = 16;

    private static final JsonMapper STRICT = JsonCodec.standardMapperBuilder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private static final Set<String> PACK_FIELDS = Set.of("name", "version", "rules", "disabledRules");
    private static final Set<String> RULE_FIELDS = Set.of("id", "description", "phase", "stages", "when", "effect",
            "severity", "message");
    private static final Map<String, Set<String>> CONDITION_FIELDS = conditionFields();
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private PolicyPackLoader() {
    }

    private static Map<String, Set<String>> conditionFields() {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        fields.put(Condition.INTENT_MATCHES, Set.of("regexes"));
        fields.put(Condition.PATH_MATCHES, Set.of("globs"));
        fields.put(Condition.PAYLOAD_MATCHES, Set.of("regexes"));
        fields.put(Condition.RISK_AT_LEAST, Set.of("level"));
        fields.put(Condition.CHANGE_SIZE_OVER, Set.of("files", "lines"));
        fields.put(Condition.RELEASE_DECISION_IS, Set.of("decision"));
        fields.put(Condition.AUTONOMY_IS, Set.of("autonomy"));
        fields.put(Condition.ALL_OF, Set.of("conditions"));
        fields.put(Condition.ANY_OF, Set.of("conditions"));
        fields.put(Condition.NOT, Set.of("condition"));
        return Map.copyOf(fields);
    }

    /**
     * Parses and validates a pack.
     *
     * @param json the pack as JSON
     * @return the pack
     * @throws PolicyPackException listing every problem
     */
    public static PolicyPack fromJson(String json) {
        JsonNode root;
        try {
            root = STRICT.readTree(json == null ? "" : json);
        } catch (JacksonException e) {
            throw new PolicyPackException(List.of("$: not valid JSON: " + e.getOriginalMessage()));
        }
        List<String> problems = new ArrayList<>();
        if (root == null || !root.isObject()) {
            throw new PolicyPackException(List.of("$: a policy pack must be a JSON object"));
        }
        unknownFields(root, PACK_FIELDS, "$", problems);
        String name = string(root, "name", "$", true, problems);
        String version = string(root, "version", "$", true, problems);
        List<String> disabled = stringList(root, "disabledRules", "$", problems);
        List<IndexedRule> rules = new ArrayList<>();
        JsonNode rulesNode = root.get("rules");
        if (rulesNode != null && !rulesNode.isNull()) {
            if (!rulesNode.isArray()) {
                problems.add("$.rules: must be an array");
            } else {
                for (int i = 0; i < rulesNode.size(); i++) {
                    DeclarativeRule rule = rule(rulesNode.get(i), "$.rules[" + i + "]", problems);
                    if (rule != null) {
                        rules.add(new IndexedRule("$.rules[" + i + "]", rule));
                    }
                }
            }
        }
        validate(name, version, rules, disabled, problems);
        if (!problems.isEmpty()) {
            throw new PolicyPackException(problems);
        }
        return new PolicyPack(name == null ? "" : name, version == null ? "" : version,
                rules.stream().map(IndexedRule::rule).toList(), disabled);
    }

    /**
     * Loads the sample pack shipped with the core ({@value #SAMPLE_RESOURCE}).
     *
     * @return the sample pack
     */
    public static PolicyPack sample() {
        return fromResource(SAMPLE_RESOURCE);
    }

    /**
     * Loads a pack from the classpath.
     *
     * @param resource absolute classpath resource name, for example {@value #SAMPLE_RESOURCE}
     * @return the pack
     * @throws PolicyPackException when the pack is invalid
     * @throws UncheckedIOException when the resource is missing or unreadable
     */
    public static PolicyPack fromResource(String resource) {
        try (InputStream in = PolicyPackLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("policy pack resource not found: " + resource));
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Validates a pack built in code with the same content and governance rules as {@link #fromJson(String)}.
     *
     * @param pack the pack
     * @throws PolicyPackException listing every problem
     */
    public static void validate(PolicyPack pack) {
        List<String> problems = new ArrayList<>();
        List<IndexedRule> rules = new ArrayList<>();
        for (int i = 0; i < pack.rules().size(); i++) {
            rules.add(new IndexedRule("$.rules[" + i + "]", pack.rules().get(i)));
        }
        validate(pack.name(), pack.version(), rules, pack.disabledRules(), problems);
        if (!problems.isEmpty()) {
            throw new PolicyPackException(problems);
        }
    }

    // =========================================================================================== content validation

    private record IndexedRule(String path, DeclarativeRule rule) {
    }

    private static void validate(@Nullable String name, @Nullable String version, List<IndexedRule> rules,
                                 List<String> disabled, List<String> problems) {
        if (name != null && name.isBlank()) {
            problems.add("$.name: must not be blank");
        }
        if (version != null && version.isBlank()) {
            problems.add("$.version: must not be blank");
        }
        List<String> builtIns = DefaultPolicyRules.ids();
        Map<String, String> firstPathById = new HashMap<>();
        for (IndexedRule indexed : rules) {
            DeclarativeRule rule = indexed.rule();
            String path = indexed.path();
            validateRule(rule, path, builtIns, problems);
            String previous = firstPathById.putIfAbsent(rule.id(), path);
            if (previous != null) {
                problems.add(path + ".id: duplicate rule id '" + rule.id() + "' (also " + previous + ".id)");
            }
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < disabled.size(); i++) {
            String id = disabled.get(i);
            String path = "$.disabledRules[" + i + "]";
            if (!builtIns.contains(id)) {
                problems.add(path + ": '" + id + "' is not a built-in rule (built-in rules: " + String.join(", ",
                        builtIns) + ")");
            } else if (PolicyPack.NON_DISABLEABLE_RULE_IDS.contains(id)) {
                problems.add(path + ": '" + id + "' cannot be disabled (the release approval gate and the secret-leak "
                        + "guard are always enforced; protected rules: " + String.join(", ",
                        new TreeSet<>(PolicyPack.NON_DISABLEABLE_RULE_IDS)) + ")");
            }
            if (!seen.add(id)) {
                problems.add(path + ": '" + id + "' is listed more than once");
            }
        }
    }

    private static void validateRule(DeclarativeRule rule, String path, List<String> builtIns, List<String> problems) {
        if (!DeclarativeRule.ID_PATTERN.matcher(rule.id()).matches()) {
            problems.add(path + ".id: '" + rule.id() + "' must match " + DeclarativeRule.ID_PATTERN.pattern()
                    + " (for example ORG-001)");
        } else if (builtIns.contains(rule.id())) {
            problems.add(path + ".id: '" + rule.id() + "' collides with a built-in rule id; disable the built-in rule "
                    + "with disabledRules and choose another id");
        }
        if (rule.message().isBlank()) {
            problems.add(path + ".message: must not be blank");
        }
        if (rule.phase() == GatePhase.RUN_START && !rule.stages().isEmpty()) {
            problems.add(path + ".stages: must be empty for RUN_START rules (no stage is active at run start)");
        }
        for (int i = 0; i < rule.stages().size(); i++) {
            if (rule.stages().get(i) == null || rule.stages().get(i).isBlank()) {
                problems.add(path + ".stages[" + i + "]: must not be blank");
            }
        }
        if (rule.effect() == Effect.WARN && rule.severity() != Severity.LOW) {
            problems.add(path + ".severity: a WARN rule always produces a LOW finding that never blocks; omit the "
                    + "severity or use LOW");
        }
        validateCondition(rule.when(), path + ".when", rule.phase(), 1, problems);
    }

    private static void validateCondition(Condition condition, String path, GatePhase phase, int depth,
                                          List<String> problems) {
        if (depth > MAX_CONDITION_DEPTH) {
            problems.add(path + ": conditions are nested deeper than " + MAX_CONDITION_DEPTH + " levels");
            return;
        }
        switch (condition) {
            case Condition.IntentMatches m -> regexes(m.regexes(), path + ".regexes", problems);
            case Condition.PayloadMatches m -> {
                regexes(m.regexes(), path + ".regexes", problems);
                if (phase != GatePhase.EXIT) {
                    problems.add(path + ": payloadMatches only applies to EXIT rules (only exit gates have a "
                            + "candidate payload)");
                }
            }
            case Condition.PathMatches m -> globs(m.globs(), path + ".globs", problems);
            case Condition.ChangeSizeOver s -> {
                if (s.files() < 0) {
                    problems.add(path + ".files: must not be negative");
                }
                if (s.lines() < 0) {
                    problems.add(path + ".lines: must not be negative");
                }
            }
            case Condition.RiskAtLeast r -> {
                // the record requires a level
            }
            case Condition.ReleaseDecisionIs r -> {
                // the record requires a decision
            }
            case Condition.AutonomyIs a -> {
                // the record requires an autonomy level
            }
            case Condition.AllOf all -> combinator(all.conditions(), path, phase, depth, problems);
            case Condition.AnyOf any -> combinator(any.conditions(), path, phase, depth, problems);
            case Condition.Not not -> validateCondition(not.condition(), path + ".condition", phase, depth + 1,
                    problems);
        }
    }

    private static void combinator(List<Condition> conditions, String path, GatePhase phase, int depth,
                                   List<String> problems) {
        if (conditions.isEmpty()) {
            problems.add(path + ".conditions: must list at least one condition");
        }
        for (int i = 0; i < conditions.size(); i++) {
            validateCondition(conditions.get(i), path + ".conditions[" + i + "]", phase, depth + 1, problems);
        }
    }

    private static void regexes(List<String> regexes, String path, List<String> problems) {
        if (regexes.isEmpty()) {
            problems.add(path + ": must list at least one regex");
        }
        for (int i = 0; i < regexes.size(); i++) {
            String regex = regexes.get(i);
            if (regex == null || regex.isBlank()) {
                problems.add(path + "[" + i + "]: must not be blank");
                continue;
            }
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                problems.add(path + "[" + i + "]: invalid regex '" + abbreviate(regex) + "': " + e.getDescription()
                        + (e.getIndex() >= 0 ? " near index " + e.getIndex() : ""));
                continue;
            }
            String unsafe = RegexGuard.problem(regex);
            if (unsafe != null) {
                problems.add(path + "[" + i + "]: unsafe regex '" + abbreviate(regex) + "': " + unsafe);
            }
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    private static void globs(List<String> globs, String path, List<String> problems) {
        if (globs.isEmpty()) {
            problems.add(path + ": must list at least one glob");
        }
        for (int i = 0; i < globs.size(); i++) {
            String glob = globs.get(i);
            String at = path + "[" + i + "]";
            if (glob == null || glob.isBlank()) {
                problems.add(at + ": must not be blank");
            } else if (glob.contains("\\")) {
                problems.add(at + ": invalid glob '" + glob + "': use forward slashes");
            } else if (glob.startsWith("/") || DRIVE.matcher(glob).matches()) {
                problems.add(at + ": invalid glob '" + glob + "': must be relative to the repository root");
            } else if (Arrays.asList(glob.split("/")).contains("..")) {
                problems.add(at + ": invalid glob '" + glob + "': '..' segments are not allowed");
            }
        }
    }

    // =========================================================================================== JSON structure

    private static @Nullable DeclarativeRule rule(JsonNode node, String path, List<String> problems) {
        if (node == null || !node.isObject()) {
            problems.add(path + ": a rule must be a JSON object");
            return null;
        }
        int before = problems.size();
        unknownFields(node, RULE_FIELDS, path, problems);
        String id = string(node, "id", path, true, problems);
        String description = string(node, "description", path, false, problems);
        GatePhase phase = enumValue(node, "phase", path, GatePhase.class, true, problems);
        List<String> stages = stringList(node, "stages", path, problems);
        Effect effect = enumValue(node, "effect", path, Effect.class, true, problems);
        Severity severity = enumValue(node, "severity", path, Severity.class, false, problems);
        String message = string(node, "message", path, true, problems);
        Condition when = null;
        JsonNode whenNode = node.get("when");
        if (whenNode == null || whenNode.isNull()) {
            problems.add(path + ".when: required");
        } else {
            when = condition(whenNode, path + ".when", 1, problems);
        }
        if (problems.size() > before || id == null || phase == null || effect == null || when == null) {
            return null;
        }
        return new DeclarativeRule(id, description == null ? "" : description, phase, stages, when, effect, severity,
                message == null ? "" : message);
    }

    private static @Nullable Condition condition(JsonNode node, String path, int depth, List<String> problems) {
        if (!node.isObject()) {
            problems.add(path + ": a condition must be a JSON object with a \"type\"");
            return null;
        }
        if (depth > MAX_CONDITION_DEPTH) {
            problems.add(path + ": conditions are nested deeper than " + MAX_CONDITION_DEPTH + " levels");
            return null;
        }
        String type = string(node, Condition.TYPE_PROPERTY, path, true, problems);
        if (type == null) {
            return null;
        }
        Set<String> fields = CONDITION_FIELDS.get(type);
        if (fields == null) {
            problems.add(path + ".type: unknown condition type '" + type + "' (allowed: "
                    + String.join(", ", new TreeSet<>(CONDITION_FIELDS.keySet())) + ")");
            return null;
        }
        Set<String> allowed = new HashSet<>(fields);
        allowed.add(Condition.TYPE_PROPERTY);
        int before = problems.size();
        unknownFields(node, allowed, path, problems);
        Condition result = switch (type) {
            case Condition.INTENT_MATCHES -> new Condition.IntentMatches(requiredList(node, "regexes", path,
                    problems));
            case Condition.PATH_MATCHES -> new Condition.PathMatches(requiredList(node, "globs", path, problems));
            case Condition.PAYLOAD_MATCHES -> new Condition.PayloadMatches(requiredList(node, "regexes", path,
                    problems));
            case Condition.RISK_AT_LEAST -> {
                RiskLevel level = enumValue(node, "level", path, RiskLevel.class, true, problems);
                yield level == null ? null : new Condition.RiskAtLeast(level);
            }
            case Condition.CHANGE_SIZE_OVER -> {
                Integer files = integer(node, "files", path, problems);
                Integer lines = integer(node, "lines", path, problems);
                yield files == null || lines == null ? null : new Condition.ChangeSizeOver(files, lines);
            }
            case Condition.RELEASE_DECISION_IS -> {
                ReleaseReadiness.GoNoGo decision = enumValue(node, "decision", path, ReleaseReadiness.GoNoGo.class,
                        true, problems);
                yield decision == null ? null : new Condition.ReleaseDecisionIs(decision);
            }
            case Condition.AUTONOMY_IS -> {
                AutonomyLevel autonomy = enumValue(node, "autonomy", path, AutonomyLevel.class, true, problems);
                yield autonomy == null ? null : new Condition.AutonomyIs(autonomy);
            }
            case Condition.ALL_OF -> {
                List<Condition> conditions = conditions(node, path, depth, problems);
                yield conditions == null ? null : new Condition.AllOf(conditions);
            }
            case Condition.ANY_OF -> {
                List<Condition> conditions = conditions(node, path, depth, problems);
                yield conditions == null ? null : new Condition.AnyOf(conditions);
            }
            case Condition.NOT -> {
                JsonNode inner = node.get("condition");
                if (inner == null || inner.isNull()) {
                    problems.add(path + ".condition: required");
                    yield null;
                }
                Condition negated = condition(inner, path + ".condition", depth + 1, problems);
                yield negated == null ? null : new Condition.Not(negated);
            }
            default -> null;
        };
        return problems.size() > before ? null : result;
    }

    private static @Nullable List<Condition> conditions(JsonNode node, String path, int depth, List<String> problems) {
        JsonNode array = node.get("conditions");
        if (array == null || array.isNull()) {
            problems.add(path + ".conditions: required");
            return null;
        }
        if (!array.isArray()) {
            problems.add(path + ".conditions: must be an array of conditions");
            return null;
        }
        List<Condition> result = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < array.size(); i++) {
            Condition inner = condition(array.get(i), path + ".conditions[" + i + "]", depth + 1, problems);
            if (inner == null) {
                ok = false;
            } else {
                result.add(inner);
            }
        }
        return ok ? result : null;
    }

    private static void unknownFields(JsonNode node, Set<String> allowed, String path, List<String> problems) {
        for (String field : node.propertyNames()) {
            if (!allowed.contains(field)) {
                problems.add(path + "." + field + ": unknown field (allowed: " + String.join(", ",
                        new TreeSet<>(allowed)) + ")");
            }
        }
    }

    private static @Nullable String string(JsonNode node, String field, String path, boolean required,
                                           List<String> problems) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                problems.add(path + "." + field + ": required");
            }
            return null;
        }
        if (!value.isString()) {
            problems.add(path + "." + field + ": must be a string");
            return null;
        }
        return value.asString();
    }

    private static List<String> stringList(JsonNode node, String field, String path, List<String> problems) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            problems.add(path + "." + field + ": must be an array of strings");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode element = value.get(i);
            if (element == null || !element.isString()) {
                problems.add(path + "." + field + "[" + i + "]: must be a string");
            } else {
                result.add(element.asString());
            }
        }
        return result;
    }

    private static List<String> requiredList(JsonNode node, String field, String path, List<String> problems) {
        if (node.get(field) == null || node.get(field).isNull()) {
            problems.add(path + "." + field + ": required");
            return List.of();
        }
        return stringList(node, field, path, problems);
    }

    private static @Nullable Integer integer(JsonNode node, String field, String path, List<String> problems) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            problems.add(path + "." + field + ": required");
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            problems.add(path + "." + field + ": must be an integer");
            return null;
        }
        return value.intValue();
    }

    private static <E extends Enum<E>> @Nullable E enumValue(JsonNode node, String field, String path,
                                                             Class<E> type, boolean required, List<String> problems) {
        String text = string(node, field, path, required, problems);
        if (text == null) {
            return null;
        }
        String wanted = text.strip().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(wanted)) {
                return constant;
            }
        }
        problems.add(path + "." + field + ": unknown value '" + text + "' (allowed: "
                + String.join(", ", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()) + ")");
        return null;
    }
}
