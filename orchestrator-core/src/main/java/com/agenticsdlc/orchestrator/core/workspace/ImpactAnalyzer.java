package com.agenticsdlc.orchestrator.core.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.DataFlow;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedEndpoint;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;

/**
 * Deterministic impact analysis of a requirement over a {@link CodebaseIndex}.
 *
 * <p>Keywords come from the normalized statement, the requirement texts and the scope (tokens of at least
 * four characters minus stop words, plus singular forms), expanded with a synonym map (alias, expiry,
 * analytics, redirect, bulk, rate limiting, security, QR, performance and reliability groups). Sentences
 * that ask to preserve behaviour ("unchanged", "keep working", ...) contribute regression keywords instead
 * of change keywords.</p>
 *
 * <p>Only modules named by the requirement are analysed (a module token such as {@code shortener} of
 * {@code url-shortener} appears in the requirement); when no module is named, all modules are.</p>
 *
 * <p>Scoring per production type: +0.5 when the simple name contains a change keyword; +0.3 per keyword
 * found in the package path below the module's base package; +0.1 per discriminating literal keyword in the
 * source tokens (capped at 0.4; synonym expansions do not count for source content, and keywords present in
 * more than half of the production types are not discriminating); +0.2 for controllers with an endpoint
 * whose HTTP method matches a requirement verb (and for services those controllers use). Types scoring at
 * least 0.3 are directly impacted (EXTEND when the requirement adds behaviour, else MODIFY); entities are
 * only directly impacted when the requirement implies a schema change, and exception and bootstrap
 * ({@code *Application}) types never are. One-hop dependents and dependencies of directly impacted types,
 * and types matching only regression keywords, are reported as REGRESSION_RISK with confidence multiplied
 * by 0.6.</p>
 *
 * <p>Migrations: when an entity is directly impacted, the latest existing migration and the proposed next version
 * are taken from the migrations of the modules that own the directly impacted entities; when those modules have
 * none, from the analysed modules (or from every module when the requirement names none). A migration belongs to the
 * module of its first path segment, like a type.</p>
 */
public final class ImpactAnalyzer {

    /** Minimum score for a direct impact. */
    public static final double DIRECT_THRESHOLD = 0.3;

    /** Confidence factor for one-hop regression risks. */
    public static final double REGRESSION_FACTOR = 0.6;

    private static final String BASE_SOURCE = "url-shortener/src/main/java/com/agenticsdlc/shortener/";

    private static final Set<String> STOP_WORDS = Set.of("that", "this", "with", "from", "into", "must", "should",
            "shall", "will", "have", "each", "every", "more", "make", "only", "also", "when", "then", "than", "they",
            "them", "their", "there", "which", "while", "where", "what", "under", "over", "such", "some", "same",
            "keep", "working", "unchanged", "existing", "support", "provide", "allow", "need", "needs", "service",
            "services", "system", "shortener", "application", "module", "feature", "features", "based", "using",
            "used", "able", "want", "ensure", "build", "given", "clarifications", "request", "requests", "response",
            "responses", "default", "defaults", "optional", "http", "https", "would", "could", "does", "done", "work",
            "works", "within", "least", "most", "many", "much", "less", "after", "before", "about", "across",
            "against", "between", "being", "been", "were", "your", "other", "others", "including", "include",
            "includes", "item", "items", "result", "results", "return", "returns", "reuse", "respect", "accept",
            "accepts", "public", "private", "class", "void", "final", "static", "string", "list", "true", "false",
            "null", "new", "create", "creates", "current", "currently", "rules", "rule",
            "semantics", "behaviour", "behavior", "change", "changes", "single", "added", "adding", "client", "clients",
            "management", "handling", "operational", "protection", "controls", "described");

    private static final Map<String, List<String>> SYNONYM_TRIGGERS = new LinkedHashMap<>();
    private static final Map<String, List<String>> SYNONYM_GROUPS = new LinkedHashMap<>();

    static {
        group("alias", List.of("alias"), List.of("alias", "code", "slug"));
        group("expire", List.of("expir", "ttl"), List.of("expire", "expiry", "expires", "ttl"));
        group("analytics", List.of("analytic", "click", "referr", "stats", "statistic"),
                List.of("click", "clicks", "analytics", "stats", "referrer"));
        group("redirect", List.of("redirect"), List.of("redirect", "resolve", "location"));
        group("bulk", List.of("bulk", "batch"), List.of("bulk", "batch", "list", "multiple"));
        group("rate", List.of("rate", "throttl"), List.of("rate", "ratelimit", "limit", "limiter", "bucket",
                "throttle"));
        group("security", List.of("secur", "ssrf", "privat", "valid", "auth"),
                List.of("validator", "ssrf", "private", "auth"));
        group("qr", List.of("qr"), List.of("qr", "image", "png"));
        group("performance", List.of("perform", "fast", "latenc", "speed", "quick", "cache"),
                List.of("cache", "caffeine", "latency", "index"));
        group("reliability", List.of("reliab", "availab", "uptime", "resilien", "retry", "retries"),
                List.of("retry", "timeout", "health", "idempotency", "resilience"));
    }

    private static final Set<String> PRESERVATION_MARKERS = Set.of("unchanged", "keep working", "keeps working",
            "continue to work", "continues to work", "backward compatible", "backwards compatible", "must not change",
            "must not break", "without breaking", "remain", "remains");
    private static final Set<String> ADDS_BEHAVIOUR = Set.of("add", "adds", "new", "extend", "extends", "support",
            "supports", "introduce", "introduces", "bulk", "enable", "enables", "allow", "allows");
    private static final Set<String> GENERIC_MODULE_TOKENS = Set.of("app", "core", "service", "api", "common", "lib",
            "main", "module", "server", "web");
    private static final Set<String> SCHEMA_WORDS = Set.of("field", "fields", "column", "columns", "attribute",
            "attributes", "store", "stores", "stored", "persist", "persists", "persisted", "table", "tables", "schema",
            "migration", "entity", "entities", "track", "tracks", "tracking", "record", "records", "save", "saves",
            "retention", "database");
    private static final Map<String, String> VERB_METHODS = Map.ofEntries(
            Map.entry("add", "POST"), Map.entry("create", "POST"), Map.entry("creation", "POST"),
            Map.entry("shorten", "POST"), Map.entry("submit", "POST"), Map.entry("bulk", "POST"),
            Map.entry("import", "POST"), Map.entry("upload", "POST"), Map.entry("get", "GET"),
            Map.entry("read", "GET"), Map.entry("fetch", "GET"), Map.entry("list", "GET"), Map.entry("view", "GET"),
            Map.entry("show", "GET"), Map.entry("redirect", "GET"), Map.entry("resolve", "GET"),
            Map.entry("delete", "DELETE"), Map.entry("remove", "DELETE"), Map.entry("deactivate", "DELETE"),
            Map.entry("disable", "DELETE"), Map.entry("update", "PUT"), Map.entry("modify", "PUT"),
            Map.entry("edit", "PUT"), Map.entry("rename", "PATCH"));

    private static void group(String key, List<String> triggers, List<String> members) {
        SYNONYM_TRIGGERS.put(key, triggers);
        SYNONYM_GROUPS.put(key, members);
    }

    /**
     * Creates the analyzer (stateless and thread-safe).
     */
    public ImpactAnalyzer() {
        // stateless
    }

    /**
     * Keywords derived from a requirement.
     *
     * @param change keywords describing what changes (including synonym expansions)
     * @param regression keywords describing behaviour that must be preserved (including synonym expansions)
     * @param literal change keywords taken literally from the requirement (no synonyms); only these count for
     *                source-content matches, synonyms only for name and package matches
     * @param httpMethods HTTP methods implied by requirement verbs
     * @param addsBehaviour whether the requirement adds behaviour (EXTEND rather than MODIFY)
     * @param schemaChange whether the requirement implies a schema change
     * @param tokens all requirement tokens (used to target modules named by the requirement)
     */
    public record Keywords(Set<String> change, Set<String> regression, Set<String> literal, Set<String> httpMethods,
                           boolean addsBehaviour, boolean schemaChange, Set<String> tokens) {
    }

    /**
     * Derives keywords from a requirement specification.
     *
     * @param spec the requirement specification
     * @return the keywords
     */
    public Keywords keywords(RequirementSpec spec) {
        List<String> pieces = new ArrayList<>();
        for (String sentence : spec.normalizedStatement().split("(?<=[.!?;:])\\s+")) {
            pieces.add(sentence);
        }
        spec.allRequirements().forEach(r -> pieces.add(r.text()));
        pieces.addAll(spec.scopeIn());
        Set<String> change = new TreeSet<>();
        Set<String> regression = new TreeSet<>();
        Set<String> methods = new TreeSet<>();
        Set<String> allTokens = new TreeSet<>();
        boolean adds = false;
        boolean schema = false;
        for (String piece : pieces) {
            String lower = piece.toLowerCase(Locale.ROOT);
            boolean preserve = PRESERVATION_MARKERS.stream().anyMatch(lower::contains);
            List<String> tokens = tokens(lower);
            allTokens.addAll(tokens);
            Set<String> target = preserve ? regression : change;
            for (String token : tokens) {
                if (!preserve) {
                    adds |= ADDS_BEHAVIOUR.contains(token);
                    schema |= SCHEMA_WORDS.contains(token);
                    String method = VERB_METHODS.get(token);
                    if (method != null) {
                        methods.add(method);
                    }
                }
                if (token.length() >= 4 && !STOP_WORDS.contains(token)) {
                    target.add(token);
                    String singular = singular(token);
                    if (singular.length() >= 3 && !STOP_WORDS.contains(singular)) {
                        target.add(singular);
                    }
                }
            }
        }
        Set<String> literal = new TreeSet<>(change);
        expand(change);
        expand(regression);
        regression.removeAll(change);
        return new Keywords(change, regression, literal, methods, adds, schema, allTokens);
    }

    /**
     * Returns the modules the requirement names (a module token such as {@code shortener} of
     * {@code url-shortener} appears in the requirement), or all modules when none is named.
     */
    private static Set<String> targetModules(List<IndexedType> production, Keywords keywords) {
        Set<String> all = new TreeSet<>();
        Set<String> named = new TreeSet<>();
        for (IndexedType type : production) {
            all.add(type.module());
            for (String token : type.module().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() >= 3 && !GENERIC_MODULE_TOKENS.contains(token) && keywords.tokens().contains(token)) {
                    named.add(type.module());
                }
            }
        }
        return named.isEmpty() ? all : named;
    }

    private static boolean neverDirect(IndexedType type) {
        return type.simpleName().endsWith("Exception") || type.simpleName().endsWith("Application");
    }

    private static void expand(Set<String> keywords) {
        Set<String> added = new TreeSet<>();
        for (String keyword : keywords) {
            SYNONYM_TRIGGERS.forEach((group, triggers) -> {
                if (triggers.stream().anyMatch(keyword::startsWith)) {
                    added.addAll(SYNONYM_GROUPS.get(group));
                }
            });
        }
        keywords.addAll(added);
    }

    private static List<String> tokens(String lower) {
        List<String> tokens = new ArrayList<>();
        for (String token : lower.split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String singular(String token) {
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("sses") || token.endsWith("ss")) {
            return token;
        }
        if (token.endsWith("es") && (token.endsWith("ches") || token.endsWith("shes") || token.endsWith("xes"))) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 3) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    /**
     * Analyses the impact of a requirement on an indexed codebase. An empty index yields a greenfield
     * proposal ({@link #proposeGreenfield(RequirementSpec)}).
     *
     * @param index the codebase index
     * @param spec the requirement specification
     * @return the impact analysis
     */
    public ImpactAnalysis analyze(CodebaseIndex index, RequirementSpec spec) {
        if (index.isEmpty()) {
            return proposeGreenfield(spec);
        }
        Keywords keywords = keywords(spec);
        List<IndexedType> allProduction = index.types().stream().filter(t -> !t.test()).toList();
        Set<String> modules = targetModules(allProduction, keywords);
        boolean modulesNamed = allProduction.stream().anyMatch(t -> !modules.contains(t.module()));
        List<IndexedType> production = allProduction.stream().filter(t -> modules.contains(t.module())).toList();
        Set<String> candidates = new TreeSet<>();
        production.forEach(t -> candidates.add(t.simpleName()));
        Set<String> discriminating = discriminating(index, production, keywords);
        Map<String, List<String>> basePackages = basePackages(production);

        Map<String, Score> change = new LinkedHashMap<>();
        Map<String, Score> regression = new LinkedHashMap<>();
        for (IndexedType type : production) {
            change.put(type.simpleName(), score(index, type, keywords.change(), keywords.literal(), discriminating,
                    basePackages));
            regression.put(type.simpleName(), score(index, type, keywords.regression(), keywords.regression(),
                    discriminating, basePackages));
        }
        // Verb boost for controllers whose endpoints match requirement verbs, and the services they use.
        Set<String> boostedServices = new TreeSet<>();
        for (IndexedType type : production) {
            if (type.kind() == TypeKind.CONTROLLER) {
                for (Endpoint e : type.endpoints()) {
                    if (keywords.httpMethods().contains(e.method())) {
                        Score s = change.get(type.simpleName());
                        s.add(0.2, "endpoint " + e.method() + " " + e.path() + " matches a requirement verb");
                        type.references().stream().filter(r -> index.find(r).map(t -> t.kind() == TypeKind.SERVICE)
                                .orElse(false)).forEach(boostedServices::add);
                        break;
                    }
                }
            }
        }
        for (String service : boostedServices) {
            Score s = change.get(service);
            if (s != null && s.value > 0) {
                s.add(0.2, "used by a controller whose endpoint matches a requirement verb");
            }
        }

        Map<String, ImpactedComponent> components = new LinkedHashMap<>();
        List<IndexedType> direct = new ArrayList<>();
        for (IndexedType type : production) {
            Score s = change.get(type.simpleName());
            if (s.value < DIRECT_THRESHOLD) {
                continue;
            }
            double confidence = Math.min(1.0, s.value);
            if (type.kind() == TypeKind.ENTITY && !keywords.schemaChange()) {
                List<String> reasons = new ArrayList<>(s.reasons);
                reasons.add("entity is involved but the requirement implies no schema change");
                components.put(type.simpleName(), component(type, confidence * REGRESSION_FACTOR, reasons,
                        ChangeNature.REGRESSION_RISK));
                continue;
            }
            if (neverDirect(type)) {
                List<String> reasons = new ArrayList<>(s.reasons);
                reasons.add("exception or bootstrap type: affected only indirectly");
                components.put(type.simpleName(), component(type, confidence * REGRESSION_FACTOR, reasons,
                        ChangeNature.REGRESSION_RISK));
                continue;
            }
            components.put(type.simpleName(), component(type, confidence, s.reasons,
                    keywords.addsBehaviour() ? ChangeNature.EXTEND : ChangeNature.MODIFY));
            direct.add(type);
        }
        for (IndexedType type : production) {
            Score r = regression.get(type.simpleName());
            if (!components.containsKey(type.simpleName()) && r.value >= DIRECT_THRESHOLD) {
                List<String> reasons = new ArrayList<>(r.reasons);
                reasons.add("requirement asks to preserve this behaviour");
                components.put(type.simpleName(), component(type, Math.min(1.0, r.value) * REGRESSION_FACTOR, reasons,
                        ChangeNature.REGRESSION_RISK));
            }
        }
        // One hop: dependents and dependencies of directly impacted types.
        Map<String, Double> hopConfidence = new TreeMap<>();
        Map<String, Set<String>> hopReasons = new TreeMap<>();
        for (IndexedType d : direct) {
            double confidence = components.get(d.simpleName()).confidence() * REGRESSION_FACTOR;
            for (IndexedType dependent : index.dependentsOf(d.simpleName())) {
                if (!dependent.test() && candidates.contains(dependent.simpleName())) {
                    hop(hopConfidence, hopReasons, dependent.simpleName(), confidence, "uses " + d.simpleName());
                }
            }
            for (String dependency : d.references()) {
                if (candidates.contains(dependency)) {
                    hop(hopConfidence, hopReasons, dependency, confidence, "used by " + d.simpleName());
                }
            }
        }
        hopConfidence.forEach((name, confidence) -> {
            if (!components.containsKey(name)) {
                index.find(name).ifPresent(t -> components.put(name, component(t, confidence,
                        new ArrayList<>(hopReasons.get(name)), ChangeNature.REGRESSION_RISK)));
            }
        });

        List<ImpactedComponent> sorted = new ArrayList<>(components.values());
        sorted.sort(Comparator.comparing((ImpactedComponent c) -> c.nature() == ChangeNature.REGRESSION_RISK)
                .thenComparing(ImpactedComponent::confidence, Comparator.reverseOrder())
                .thenComparing(ImpactedComponent::name));

        List<IndexedType> directControllers = direct.stream().filter(t -> t.kind() == TypeKind.CONTROLLER).toList();
        List<IndexedType> directEntities = direct.stream().filter(t -> t.kind() == TypeKind.ENTITY).toList();
        List<ImpactedEndpoint> endpoints = directControllers.stream().flatMap(c -> c.endpoints().stream())
                .map(e -> new ImpactedEndpoint(e.method(), e.path(), e.controller())).toList();
        List<String> entities = new ArrayList<>();
        for (ImpactedComponent c : sorted) {
            if (c.type() == ComponentType.ENTITY) {
                index.find(c.name()).ifPresent(t -> entities.add(t.simpleName()
                        + (t.table() == null ? "" : " (" + t.table() + ")")));
            }
        }
        List<String> migrations = new ArrayList<>();
        if (!directEntities.isEmpty()) {
            List<String> scoped = scopedMigrations(index, directEntities, modules, modulesNamed);
            if (!scoped.isEmpty()) {
                migrations.add(scoped.getLast());
            }
            migrations.add("proposed: V" + nextMigrationVersion(scoped) + "__" + slug(keywords) + ".sql");
        }
        List<DataFlow> dataFlows = dataFlows(index, directControllers);
        List<String> riskFactors = riskFactors(index, direct);
        List<String> suggestedTests = suggestedTests(index, direct, modules);
        long regressionCount = sorted.stream().filter(c -> c.nature() == ChangeNature.REGRESSION_RISK).count();
        String summary = "Indexed " + index.indexedFiles() + " files in " + index.rootName() + " (analysed modules: "
                + String.join(", ", modules) + "); " + direct.size() + " components directly impacted ("
                + String.join(", ", direct.stream().limit(6).map(IndexedType::simpleName).toList())
                + (direct.size() > 6 ? ", ..." : "") + "), " + regressionCount + " regression risks, "
                + endpoints.size() + " endpoints affected.";
        return new ImpactAnalysis(index.rootName(), index.indexedFiles(), true, sorted, endpoints, entities, migrations,
                dataFlows, riskFactors, suggestedTests, summary);
    }

    private static void hop(Map<String, Double> confidence, Map<String, Set<String>> reasons, String name, double value,
                            String reason) {
        confidence.merge(name, value, Math::max);
        reasons.computeIfAbsent(name, k -> new TreeSet<>()).add(reason);
    }

    private static ImpactedComponent component(IndexedType type, double confidence, List<String> reasons,
                                               ChangeNature nature) {
        double rounded = Math.round(confidence * 100.0) / 100.0;
        return new ImpactedComponent(type.simpleName(), type.kind().componentType(), type.path(), rounded, reasons,
                nature);
    }

    private static Set<String> discriminating(CodebaseIndex index, List<IndexedType> production, Keywords keywords) {
        Set<String> all = new TreeSet<>(keywords.change());
        all.addAll(keywords.regression());
        if (production.size() < 4) {
            return all;
        }
        Set<String> result = new TreeSet<>();
        for (String keyword : all) {
            long df = production.stream().filter(t -> index.contentTokens(t.path()).contains(keyword)).count();
            if (df * 2 <= production.size()) {
                result.add(keyword);
            }
        }
        return result;
    }

    private static Map<String, List<String>> basePackages(List<IndexedType> production) {
        Map<String, List<String>> base = new HashMap<>();
        for (IndexedType type : production) {
            List<String> pkg = packageSegments(type);
            base.merge(type.module(), pkg, (a, b) -> {
                int i = 0;
                while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) {
                    i++;
                }
                return List.copyOf(a.subList(0, i));
            });
        }
        return base;
    }

    private static List<String> packageSegments(IndexedType type) {
        int dot = type.fqcn().lastIndexOf('.');
        if (dot < 0) {
            return List.of();
        }
        return List.of(type.fqcn().substring(0, dot).toLowerCase(Locale.ROOT).split("\\."));
    }

    private static Score score(CodebaseIndex index, IndexedType type, Set<String> keywords, Set<String> contentKeywords,
                               Set<String> discriminating, Map<String, List<String>> basePackages) {
        Score score = new Score();
        if (keywords.isEmpty()) {
            return score;
        }
        String name = type.simpleName().toLowerCase(Locale.ROOT);
        List<String> nameHits = keywords.stream().filter(name::contains).toList();
        if (!nameHits.isEmpty()) {
            score.add(0.5, "name matches " + String.join(", ", nameHits));
        }
        List<String> segments = packageSegments(type);
        List<String> base = basePackages.getOrDefault(type.module(), List.of());
        List<String> local = segments.size() > base.size() ? segments.subList(base.size(), segments.size()) : List.of();
        for (String keyword : keywords) {
            if (local.stream().anyMatch(s -> s.equals(keyword) || (keyword.length() >= 4 && s.contains(keyword)))) {
                score.add(0.3, "package matches " + keyword);
            }
        }
        Set<String> tokens = index.contentTokens(type.path());
        List<String> contentHits = contentKeywords.stream().filter(discriminating::contains).filter(tokens::contains)
                .toList();
        if (!contentHits.isEmpty()) {
            score.add(Math.min(0.4, 0.1 * contentHits.size()), "source mentions " + String.join(", ", contentHits));
        }
        return score;
    }

    /**
     * Returns the migrations (oldest first) relevant to a schema change of the given entities: those of the entities'
     * modules; when these have none, those of the analysed modules, or all of them when no module was named.
     */
    private static List<String> scopedMigrations(CodebaseIndex index, List<IndexedType> entities, Set<String> modules,
                                                 boolean modulesNamed) {
        Set<String> entityModules = new TreeSet<>();
        entities.forEach(e -> entityModules.add(e.module()));
        List<String> byEntity = migrationsOf(index, entityModules);
        if (!byEntity.isEmpty()) {
            return byEntity;
        }
        return modulesNamed ? migrationsOf(index, modules) : index.migrations();
    }

    private static List<String> migrationsOf(CodebaseIndex index, Set<String> modules) {
        return index.migrations().stream().filter(m -> modules.contains(moduleOf(m))).toList();
    }

    /** Module of a workspace-relative path: its first segment (as for {@link IndexedType#module()}). */
    private static String moduleOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static int nextMigrationVersion(List<String> migrations) {
        int max = 0;
        for (String migration : migrations) {
            List<Integer> version = CodebaseIndexer.flywayVersion(migration);
            if (!version.isEmpty()) {
                max = Math.max(max, version.getFirst());
            }
        }
        return max + 1;
    }

    private static String slug(Keywords keywords) {
        List<String> words = keywords.change().stream().filter(w -> w.matches("[a-z]+")).limit(3).toList();
        return words.isEmpty() ? "change" : String.join("_", words);
    }

    private static List<DataFlow> dataFlows(CodebaseIndex index, List<IndexedType> controllers) {
        Set<DataFlow> flows = new LinkedHashSet<>();
        for (IndexedType controller : controllers) {
            for (String serviceName : controller.references()) {
                IndexedType service = index.find(serviceName).orElse(null);
                if (service == null || service.kind() != TypeKind.SERVICE) {
                    continue;
                }
                boolean any = false;
                for (String repositoryName : service.references()) {
                    IndexedType repository = index.find(repositoryName).orElse(null);
                    if (repository == null || repository.kind() != TypeKind.REPOSITORY) {
                        continue;
                    }
                    String table = repository.references().stream().map(index::find)
                            .flatMap(java.util.Optional::stream)
                            .filter(t -> t.kind() == TypeKind.ENTITY && t.table() != null)
                            .map(IndexedType::table).findFirst().orElse(repository.simpleName());
                    flows.add(new DataFlow(controller.simpleName(), table, service.simpleName() + " -> "
                            + repository.simpleName()));
                    any = true;
                }
                if (!any) {
                    flows.add(new DataFlow(controller.simpleName(), service.simpleName(), "method call"));
                }
            }
        }
        return List.copyOf(flows);
    }

    private static List<String> riskFactors(CodebaseIndex index, List<IndexedType> direct) {
        List<String> factors = new ArrayList<>();
        List<String> entities = names(direct, TypeKind.ENTITY);
        if (!entities.isEmpty()) {
            factors.add("schema change: " + String.join(", ", entities));
        }
        List<String> controllers = names(direct, TypeKind.CONTROLLER);
        if (!controllers.isEmpty()) {
            factors.add("public API change: " + String.join(", ", controllers));
        }
        List<String> security = direct.stream().filter(t -> t.kind() == TypeKind.FILTER
                        || t.simpleName().matches(".*(Security|Validator|Auth|RateLimit).*"))
                .map(IndexedType::simpleName).toList();
        if (!security.isEmpty()) {
            factors.add("filter/security change: " + String.join(", ", security));
        }
        for (IndexedType service : direct) {
            if (service.kind() == TypeKind.SERVICE) {
                long dependents = index.dependentsOf(service.simpleName()).stream().filter(t -> !t.test()).count();
                if (dependents > 3) {
                    factors.add("shared service change: " + service.simpleName() + " has " + dependents
                            + " dependents");
                }
            }
        }
        return factors;
    }

    private static List<String> names(List<IndexedType> types, TypeKind kind) {
        return types.stream().filter(t -> t.kind() == kind).map(IndexedType::simpleName).toList();
    }

    private static List<String> suggestedTests(CodebaseIndex index, List<IndexedType> direct, Set<String> modules) {
        Set<String> names = new TreeSet<>();
        direct.forEach(t -> names.add(t.simpleName()));
        Set<String> tests = new TreeSet<>();
        for (IndexedType test : index.types()) {
            if (test.test() && modules.contains(test.module())
                    && test.references().stream().anyMatch(names::contains)) {
                tests.add("re-run: " + test.path());
            }
        }
        for (IndexedType type : direct) {
            if (type.kind() == TypeKind.CONTROLLER || type.kind() == TypeKind.SERVICE) {
                String testName = type.simpleName() + "Test";
                boolean exists = index.types().stream().anyMatch(t -> t.test() && t.simpleName().equals(testName));
                tests.add((exists ? "extend: " : "new: ") + testName);
            }
        }
        return List.copyOf(tests);
    }

    /**
     * Proposes a greenfield module structure when there is no codebase: a controller, service, repository
     * and entity per noun group of the requirement.
     *
     * @param spec the requirement specification
     * @return the impact analysis
     */
    public ImpactAnalysis proposeGreenfield(RequirementSpec spec) {
        String text = (spec.normalizedStatement() + " " + String.join(" ",
                spec.allRequirements().stream().map(RequirementSpec.Requirement::text).toList()))
                .toLowerCase(Locale.ROOT);
        Set<String> tokens = new TreeSet<>(tokens(text));
        List<NounGroup> groups = NounGroup.detect(tokens);
        List<ImpactedComponent> components = new ArrayList<>();
        List<DataFlow> flows = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        for (NounGroup group : groups) {
            String reason = "proposed for the " + group.label() + " requirements";
            components.add(proposed(group.controller(), ComponentType.CONTROLLER, "api", reason));
            if (group.redirectController() != null && tokens.stream().anyMatch(t -> t.startsWith("redirect"))) {
                components.add(proposed(group.redirectController(), ComponentType.CONTROLLER, "api", reason));
            }
            components.add(proposed(group.service(), ComponentType.SERVICE, "service", reason));
            components.add(proposed(group.repository(), ComponentType.REPOSITORY, "repository", reason));
            components.add(proposed(group.entity(), ComponentType.ENTITY, "domain", reason));
            entities.add(group.entity() + " (" + group.table() + ")");
            flows.add(new DataFlow(group.controller(), group.table(), group.service() + " -> " + group.repository()));
            tests.add("new: " + group.controller() + "Test");
            tests.add("new: " + group.service() + "Test");
        }
        List<String> risks = new ArrayList<>();
        risks.add("new public API");
        risks.add("new persistent schema: " + String.join(", ", entities));
        if (tokens.contains("url") || tokens.contains("urls") || tokens.contains("redirect")) {
            risks.add("handles user-supplied URLs (SSRF and open-redirect exposure)");
        }
        return new ImpactAnalysis("none", 0, false, components, List.of(), entities,
                List.of("proposed: V1__init.sql"), flows, risks, tests,
                "No existing codebase; greenfield module structure proposed (" + groups.size() + " component groups: "
                        + String.join(", ", groups.stream().map(NounGroup::label).toList()) + ").");
    }

    private static ImpactedComponent proposed(String name, ComponentType type, String layer, String reason) {
        return new ImpactedComponent(name, type, BASE_SOURCE + layer + "/" + name + ".java", 0.8, List.of(reason),
                ChangeNature.NEW);
    }

    /**
     * A noun group used for greenfield proposals.
     */
    private record NounGroup(String label, List<String> triggers, String controller,
                             @org.jspecify.annotations.Nullable String redirectController,
                             String service, String repository, String entity, String table) {

        private static final List<NounGroup> GROUPS = List.of(
                new NounGroup("link management", List.of("link", "url", "shorten", "alias", "expir", "redirect"),
                        "LinkController", "RedirectController", "LinkService", "LinkRepository", "Link", "links"),
                new NounGroup("click analytics", List.of("click", "analytic", "referr", "stats", "statistic"),
                        "AnalyticsController", null, "AnalyticsService", "ClickEventRepository", "ClickEvent",
                        "click_events"),
                new NounGroup("user accounts", List.of("user", "account"), "UserController", null, "UserService",
                        "UserRepository", "UserAccount", "user_accounts"));

        static List<NounGroup> detect(Set<String> tokens) {
            List<NounGroup> found = new ArrayList<>();
            for (NounGroup group : GROUPS) {
                if (tokens.stream().anyMatch(t -> group.triggers().stream().anyMatch(t::startsWith))) {
                    found.add(group);
                }
            }
            if (found.isEmpty()) {
                found.add(new NounGroup("feature", List.of(), "FeatureController", null, "FeatureService",
                        "FeatureRepository", "FeatureRecord", "feature_records"));
            }
            return found;
        }
    }

    private static final class Score {
        private double value;
        private final List<String> reasons = new ArrayList<>();

        void add(double delta, String reason) {
            value += delta;
            reasons.add(reason);
        }
    }
}
