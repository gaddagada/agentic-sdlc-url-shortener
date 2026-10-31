package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.Alternative;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.ApiChange;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.Component;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.DataHandling;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.DataModelChange;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.Decision;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec.NfrStrategy;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;

/**
 * Deterministic architect ({@code design}): derives components from the impact analysis (existing components keep
 * their indexed path), API changes from
 * requirement verbs and nouns (create, redirect, analytics, delete, get, bulk, alias, expiry), data model
 * changes from entities and nouns, NFR strategies per category, security considerations, personal data
 * handling, alternatives (exactly one chosen), decisions and the design risk
 * ({@code max(preliminary, HIGH for breaking or incompatible changes, MEDIUM for any API or data change)}).
 * Behaviour that the requirement asks to preserve ("unchanged", "keep working") does not produce changes.
 *
 * <p>Endpoints the requirement names explicitly ({@code METHOD /path}, for example
 * {@code GET /api/v1/analytics/campaigns/{campaign}}, in the statement or a functional requirement) become added
 * API changes ahead of the keyword-derived ones (which they replace for the same method and path); an explicitly
 * named endpoint that already exists in the codebase is not reported as added.</p>
 */
public final class ArchitectureAgent implements StageAgent {

    /** Maximum number of changed components; lower-confidence ones are reported as deferred risk. */
    static final int MAX_COMPONENTS = 10;

    private static final List<String> PRESERVE = List.of("unchanged", "keep working", "keeps working",
            "continue to work", "backward compatible", "must not change", "must not break");
    private static final Pattern UP_TO = Pattern.compile("up to (\\d+)");
    /** An explicit endpoint mention: an upper-case HTTP method followed by an absolute path. */
    private static final Pattern EXPLICIT_ENDPOINT = Pattern.compile(
            "(?<![A-Za-z0-9_])(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(/[A-Za-z0-9._~%{}:/\\-]*)");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");
    /** Summary prefix of the API changes for endpoints the requirement names explicitly. */
    static final String EXPLICIT_ENDPOINT_SUMMARY = "Added endpoint named by the requirement: ";

    /**
     * Creates the agent.
     */
    public ArchitectureAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.ARCHITECTURE;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        RequirementSpec spec = context.require(RequirementSpec.class);
        ImpactAnalysis impact = context.require(ImpactAnalysis.class);
        DesignSpec design = design(spec, impact);
        return StageResult.success(design, design.components().size() + " components, "
                + design.apiChanges().size() + " API changes, " + design.dataModelChanges().size()
                + " data model changes; risk " + design.risk().level());
    }

    /**
     * Derives the design.
     *
     * @param spec requirement specification
     * @param impact impact analysis
     * @return the design
     */
    public DesignSpec design(RequirementSpec spec, ImpactAnalysis impact) {
        String changeText = changeText(spec);
        List<String> tokens = Text.tokens(changeText);
        boolean existing = impact.existingCodebase();
        List<ApiChange> apiChanges = apiChanges(changeText, tokens, impact, explicitEndpoints(spec));

        Map<String, Component> components = new LinkedHashMap<>();
        List<String> deferred = new ArrayList<>();
        for (ImpactedComponent c : impact.components()) {
            if (c.nature() == ChangeNature.REGRESSION_RISK || c.type() == ComponentType.TEST) {
                continue;
            }
            if (components.size() >= MAX_COMPONENTS) {
                deferred.add(c.name());
                continue;
            }
            boolean isNew = c.nature() == ChangeNature.NEW;
            components.put(c.name(), new Component(c.name(), responsibility(c.type(), c.name(), c.nature()),
                    c.type().name(), isNew, isNew ? "" : c.path()));
        }
        boolean hasController = components.values().stream().anyMatch(c -> "CONTROLLER".equals(c.type()));
        if (!apiChanges.isEmpty() && !hasController) {
            String path = apiChanges.getFirst().path();
            String name = existingController(impact, path).orElse(controllerFor(path));
            Optional<ImpactedComponent> known = impact.components().stream().filter(c -> c.name().equals(name))
                    .findFirst();
            components.put(name, new Component(name, "Exposes the new endpoints", "CONTROLLER", known.isEmpty(),
                    known.filter(c -> c.nature() != ChangeNature.NEW).map(ImpactedComponent::path).orElse("")));
        }
        for (ApiChange api : apiChanges) {
            String dto = leadingType(api.requestSchema());
            if (dto != null && !components.containsKey(dto)
                    && impact.components().stream().noneMatch(c -> c.name().equals(dto))) {
                components.put(dto, new Component(dto, "Request payload of " + api.method() + " " + api.path(),
                        "OTHER", true));
            }
        }
        List<DataModelChange> dataChanges = dataModelChanges(changeText, tokens, impact);
        RiskAssessment risk = risk(spec, apiChanges, dataChanges, deferred);
        String overview = "Design for: " + spec.intent() + ". Classification " + spec.classification() + "; "
                + components.size() + " components (" + components.values().stream().filter(Component::isNew).count()
                + " new), " + apiChanges.size() + " API changes, " + dataChanges.size() + " data model changes. "
                + (existing ? "The change extends the existing layered service (controller, service, repository) "
                + "and reuses its validation and rate limiting."
                : "A layered Spring Boot service (controller, service, repository, entity) with a relational "
                + "database is proposed.");
        return new DesignSpec(overview, List.copyOf(components.values()), apiChanges, dataChanges,
                sequenceFlows(apiChanges, impact, components.values()), nfrStrategies(spec),
                securityConsiderations(tokens, apiChanges), dataHandling(tokens), alternatives(existing),
                risk, decisions(tokens, apiChanges, existing));
    }

    /** Text of the requirement without the sentences that only ask to preserve behaviour. */
    static String changeText(RequirementSpec spec) {
        StringBuilder text = new StringBuilder();
        for (String sentence : Text.sentences(spec.normalizedStatement())) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (PRESERVE.stream().noneMatch(lower::contains)) {
                text.append(sentence).append(' ');
            }
        }
        return text.toString().strip();
    }

    /**
     * Returns the endpoints the requirement names explicitly ({@code METHOD /path}) in the sentences of the
     * statement and the functional requirements that do not only ask to preserve behaviour, as added API changes in
     * order of first mention (one per method and path).
     *
     * @param spec requirement specification
     * @return the added API changes
     */
    static List<ApiChange> explicitEndpoints(RequirementSpec spec) {
        List<String> texts = new ArrayList<>(Text.sentences(spec.normalizedStatement()));
        spec.functional().forEach(r -> texts.add(r.text()));
        Map<String, ApiChange> found = new LinkedHashMap<>();
        for (String text : texts) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (PRESERVE.stream().anyMatch(lower::contains)) {
                continue;
            }
            Matcher m = EXPLICIT_ENDPOINT.matcher(text);
            while (m.find()) {
                String method = m.group(1);
                String path = m.group(2).replaceAll("[.,:;]+$", "");
                if (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                String key = method + " " + path;
                if (path.isEmpty() || found.containsKey(key)) {
                    continue;
                }
                boolean body = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
                List<String> errors = new ArrayList<>();
                if (body) {
                    errors.add("400 validation error");
                }
                Matcher variable = PATH_VARIABLE.matcher(path);
                if (variable.find()) {
                    errors.add("404 unknown " + variable.group(1));
                }
                found.put(key, new ApiChange(method, path, EXPLICIT_ENDPOINT_SUMMARY + Text.shorten(text, 200),
                        body ? "request body as specified by the requirement" : "none",
                        "response as specified by the requirement", errors, false));
            }
        }
        return List.copyOf(found.values());
    }

    private static List<ApiChange> apiChanges(String text, List<String> tokens, ImpactAnalysis impact,
                                              List<ApiChange> explicit) {
        boolean existing = impact.existingCodebase();
        Set<String> existingEndpoints = new LinkedHashSet<>();
        impact.endpoints().forEach(e -> existingEndpoints.add(e.method() + " " + e.path()));
        String lower = text.toLowerCase(Locale.ROOT);
        boolean links = Text.anyToken(tokens, List.of("link", "links", "url", "urls", "shorten*", "alias*"));
        List<ApiChange> changes = new ArrayList<>(explicit);
        Set<String> named = new LinkedHashSet<>();
        explicit.forEach(e -> named.add(e.method() + " " + e.path()));
        if (Text.anyToken(tokens, List.of("bulk", "batch"))) {
            Matcher m = UP_TO.matcher(lower);
            String max = m.find() ? m.group(1) : "100";
            changes.add(new ApiChange("POST", "/api/v1/links/bulk", "Create up to " + max
                    + " short links in one request with per-item results (partial failure)",
                    "BulkCreateLinksRequest { items: [CreateLinkRequest] (1.." + max + ") }",
                    "BulkCreateLinksResponse { results: [{ index, status, link?, error? }] }",
                    List.of("400 validation error", "422 too many items or invalid item", "429 rate limited"), false));
        }
        boolean create = Text.anyToken(tokens, List.of("create", "creation", "shorten*", "short"));
        if (create && links) {
            changes.add(new ApiChange("POST", "/api/v1/links", "Create a short link",
                    "CreateLinkRequest { url, customAlias?, ttlSeconds?, expiresAt? }",
                    "LinkResponse { code, shortUrl, originalUrl, status, createdAt, expiresAt?, clickCount }",
                    List.of("400 validation error", "409 alias taken", "422 URL rejected by policy",
                            "429 rate limited"), false));
        } else if (links && Text.anyToken(tokens, List.of("alias*", "expir*", "ttl"))) {
            changes.add(new ApiChange("POST", "/api/v1/links", "Accept optional customAlias and expiry on create",
                    "CreateLinkRequest { url, customAlias?, ttlSeconds?, expiresAt? }", "LinkResponse",
                    List.of("400 validation error", "409 alias taken"), false));
        }
        if (Text.anyToken(tokens, List.of("redirect*", "resolve*"))) {
            changes.add(new ApiChange("GET", "/{code}", "Redirect to the original URL with HTTP 302", "none",
                    "302 Found with Location header", List.of("404 unknown code", "410 expired or disabled"), false));
        }
        if (Text.anyToken(tokens, List.of("analytic*", "click", "clicks", "referrer*", "stats"))) {
            changes.add(new ApiChange("GET", "/api/v1/links/{code}/analytics", "Aggregated click analytics", "none",
                    "AnalyticsResponse { totalClicks, uniqueVisitors, clicksByDay, topReferrers }",
                    List.of("404 unknown code"), false));
        }
        if (links && Text.anyToken(tokens, List.of("delete*", "deactivat*", "disable"))) {
            changes.add(new ApiChange("DELETE", "/api/v1/links/{code}", "Deactivate a short link", "none",
                    "204 No Content", List.of("404 unknown code"), false));
        }
        if (links && Text.anyToken(tokens, List.of("metadata", "lookup", "get", "view"))) {
            changes.add(new ApiChange("GET", "/api/v1/links/{code}", "Link metadata", "none", "LinkResponse",
                    List.of("404 unknown code"), false));
        }
        if (!named.isEmpty()) {
            // an endpoint named explicitly replaces a keyword-derived proposal for the same method and path
            changes.removeIf(c -> !explicit.contains(c) && named.contains(c.method() + " " + c.path()));
        }
        if (!existing) {
            return changes;
        }
        // Brownfield: keep new endpoints and modifications explicitly requested; drop unchanged existing ones.
        List<ApiChange> filtered = new ArrayList<>();
        for (ApiChange change : changes) {
            boolean exists = existingEndpoints.contains(change.method() + " " + change.path());
            boolean modification = change.summary().startsWith("Accept optional");
            if (!exists || modification) {
                filtered.add(change);
            }
        }
        return filtered;
    }

    private static List<DataModelChange> dataModelChanges(String text, List<String> tokens, ImpactAnalysis impact) {
        List<DataModelChange> changes = new ArrayList<>();
        if (!impact.existingCodebase()) {
            int version = 1;
            for (String entity : impact.entities()) {
                String name = entity.contains(" ") ? entity.substring(0, entity.indexOf(' ')) : entity;
                String table = entity.contains("(") ? entity.substring(entity.indexOf('(') + 1, entity.indexOf(')'))
                        : name.toLowerCase(Locale.ROOT) + "s";
                String columns = switch (name) {
                    case "Link" -> "id, code, original_url, status, created_at, expires_at, click_count"
                            + (Text.anyToken(tokens, List.of("alias*")) ? ", custom_alias" : "");
                    case "ClickEvent" -> "id, link_id, occurred_at, referrer_host, user_agent_family, ip_hash";
                    case "UserAccount" -> "id, email_hash, created_at";
                    default -> "id, name, created_at";
                };
                changes.add(new DataModelChange(name, "create table " + table + " (" + columns + ")",
                        "V" + version + "__create_" + table + ".sql", true));
                version++;
            }
            return changes;
        }
        String proposed = impact.migrations().stream().filter(m -> m.startsWith("proposed: "))
                .map(m -> m.substring("proposed: ".length())).findFirst().orElse(null);
        if (proposed == null) {
            return changes;
        }
        for (ImpactedComponent c : impact.components()) {
            if (c.type() != ComponentType.ENTITY || c.nature() == ChangeNature.REGRESSION_RISK) {
                continue;
            }
            String table = impact.entities().stream().filter(e -> e.startsWith(c.name() + " ("))
                    .map(e -> e.substring(e.indexOf('(') + 1, e.indexOf(')'))).findFirst()
                    .orElse(c.name().toLowerCase(Locale.ROOT));
            String column = columnFor(tokens);
            changes.add(new DataModelChange(c.name(), "add column " + column + " to " + table, proposed, true));
        }
        return changes;
    }

    private static String columnFor(List<String> tokens) {
        if (Text.anyToken(tokens, List.of("alias*"))) {
            return "custom_alias";
        }
        if (Text.anyToken(tokens, List.of("expir*", "ttl"))) {
            return "expires_at";
        }
        if (Text.anyToken(tokens, List.of("qr"))) {
            return "qr_code_path";
        }
        if (Text.anyToken(tokens, List.of("tag", "tags"))) {
            return "tags";
        }
        if (Text.anyToken(tokens, List.of("title"))) {
            return "title";
        }
        return "metadata";
    }

    private static String responsibility(ComponentType type, String name, ChangeNature nature) {
        String verb = switch (nature) {
            case NEW -> "New: ";
            case EXTEND -> "Extend: ";
            default -> "Modify: ";
        };
        return verb + switch (type) {
            case CONTROLLER -> "HTTP endpoints, request validation and error mapping (" + name + ")";
            case SERVICE -> "business rules and transactions (" + name + ")";
            case REPOSITORY -> "persistence queries (" + name + ")";
            case ENTITY -> "persistent state and invariants (" + name + ")";
            case CONFIG -> "configuration (" + name + ")";
            case FILTER -> "request filtering such as rate limiting (" + name + ")";
            case null, default -> "supporting logic (" + name + ")";
        };
    }

    /**
     * Returns the controller of the impact analysis (whatever its change nature) that serves the resource of a path:
     * the first resource segment as is or in singular form ({@code /api/v1/analytics/...} is served by an existing
     * {@code AnalyticsController}, {@code /api/v1/links/...} by a {@code LinkController}).
     */
    private static java.util.Optional<String> existingController(ImpactAnalysis impact, String path) {
        String segment = resourceSegment(path);
        if (segment == null) {
            return java.util.Optional.empty();
        }
        Set<String> candidates = Set.of(Text.capitalize(segment) + "Controller",
                Text.capitalize(Text.singular(segment)) + "Controller");
        return impact.components().stream().filter(c -> c.type() == ComponentType.CONTROLLER)
                .map(ImpactedComponent::name).filter(candidates::contains).findFirst();
    }

    private static @org.jspecify.annotations.Nullable String resourceSegment(String path) {
        for (String segment : path.split("/")) {
            if (!segment.isBlank() && !segment.equals("api") && !segment.matches("v\\d+") && !segment.startsWith("{")) {
                return segment;
            }
        }
        return null;
    }

    static String controllerFor(String path) {
        if (path.startsWith("/{")) {
            return "RedirectController";
        }
        for (String segment : path.split("/")) {
            if (!segment.isBlank() && !segment.equals("api") && !segment.matches("v\\d+") && !segment.startsWith("{")) {
                return Text.capitalize(Text.singular(segment)) + "Controller";
            }
        }
        return "ApiController";
    }

    /**
     * Chooses the controller that hosts an endpoint: redirects go to a Redirect controller, analytics paths to an
     * Analytics controller, everything else to a Link controller; falls back to the first controller.
     *
     * @param controllers candidate controller names
     * @param path endpoint path
     * @return the controller, or null when there is none
     */
    static @org.jspecify.annotations.Nullable String assignController(List<String> controllers, String path) {
        String preferred = path.startsWith("/{") ? "Redirect" : path.contains("analytics") ? "Analytics" : "Link";
        return controllers.stream().filter(c -> c.contains(preferred)).findFirst()
                .orElse(controllers.isEmpty() ? null : controllers.getFirst());
    }

    private static @org.jspecify.annotations.Nullable String leadingType(String schema) {
        Matcher m = Pattern.compile("^([A-Z][A-Za-z0-9]+)\\b").matcher(schema.strip());
        return m.find() && !schema.startsWith("none") ? m.group(1) : null;
    }

    private static List<String> sequenceFlows(List<ApiChange> apiChanges, ImpactAnalysis impact,
                                              java.util.Collection<Component> components) {
        List<String> flows = new ArrayList<>();
        List<String> controllers = components.stream().filter(c -> "CONTROLLER".equals(c.type()))
                .map(Component::name).toList();
        for (ApiChange api : apiChanges) {
            String assigned = assignController(controllers, api.path());
            String controller = assigned != null ? assigned : controllerFor(api.path());
            String downstream = impact.dataFlows().stream().filter(f -> f.from().equals(controller))
                    .map(f -> f.via() + " -> " + f.to()).findFirst().orElse("service -> repository -> database");
            flows.add("Client -> " + api.method() + " " + api.path() + " -> " + controller + " -> " + downstream);
        }
        if (flows.isEmpty()) {
            impact.dataFlows().forEach(f -> flows.add(f.from() + " -> " + f.via() + " -> " + f.to()));
        }
        return flows;
    }

    private static List<NfrStrategy> nfrStrategies(RequirementSpec spec) {
        List<NfrStrategy> strategies = new ArrayList<>();
        for (RequirementSpec.Requirement nfr : spec.nonFunctional()) {
            String strategy = switch (nfr.category()) {
                case PERFORMANCE -> "Cache link lookups on the redirect path (Caffeine, short TTL), record clicks "
                        + "asynchronously, index lookup columns, and verify with a load test against the target";
                case RELIABILITY -> "Health checks and readiness probes, timeouts on outbound calls, bounded "
                        + "retries with backoff and idempotency keys for create operations";
                case SECURITY -> "Strict input validation (scheme allow-list, length limits), SSRF guard blocking "
                        + "private, loopback and link-local targets, per-client rate limiting on create endpoints";
                case COMPLIANCE -> "Hash personal data with a rotating salt, enforce a retention period and audit "
                        + "access to analytics";
                case OPERABILITY -> "Expose health, info and metrics endpoints, structured logging, dashboards and "
                        + "alerts on error rate and latency";
                case null, default -> "Document the behaviour and validate it with acceptance tests";
            };
            strategies.add(new NfrStrategy(nfr.id(), strategy));
        }
        return strategies;
    }

    private static List<String> securityConsiderations(List<String> tokens, List<ApiChange> apiChanges) {
        List<String> considerations = new ArrayList<>();
        considerations.add("Input validation: URL scheme allow-list (http, https), maximum length 2048, no "
                + "credentials in URLs; validate every item of batch requests");
        if (Text.anyToken(tokens, List.of("url", "urls", "link", "links", "redirect*", "shorten*"))) {
            considerations.add("SSRF and open-redirect guard: block loopback, private, link-local and cloud "
                    + "metadata targets");
        }
        boolean creates = apiChanges.stream().anyMatch(a -> a.method().equals("POST"));
        if (creates || Text.anyToken(tokens, List.of("create", "creation", "shorten*"))) {
            considerations.add("Rate limiting: apply the per-client token bucket to every public create endpoint");
        }
        boolean mutating = apiChanges.stream().anyMatch(a -> !a.method().equals("GET"));
        if (mutating || creates) {
            considerations.add("Mutating endpoints require authentication in production (API key or OIDC); the "
                    + "prototype documents the missing auth as an accepted risk");
        }
        considerations.add("Never log secrets or full client IP addresses");
        return considerations;
    }

    private static DataHandling dataHandling(List<String> tokens) {
        Set<String> pii = new LinkedHashSet<>();
        if (Text.anyToken(tokens, List.of("click", "clicks", "analytic*", "visitor*", "referrer*"))) {
            pii.add("client IP address (hashed with a daily salt, never stored raw)");
            pii.add("user agent (family only)");
            pii.add("referrer host");
        }
        if (tokens.contains("ip")) {
            pii.add("client IP address (hashed with a daily salt, never stored raw)");
        }
        if (Text.anyToken(tokens, List.of("email*"))) {
            pii.add("email address");
        }
        if (tokens.contains("name") && Text.anyToken(tokens, List.of("user", "users", "customer*"))) {
            pii.add("user name");
        }
        if (pii.isEmpty()) {
            return new DataHandling(List.of(), "not applicable (no personal data processed)", "not applicable");
        }
        return new DataHandling(List.copyOf(pii), "90 days", "hashed with rotating salt");
    }

    private static List<Alternative> alternatives(boolean existing) {
        if (existing) {
            return List.of(
                    new Alternative("Extend the existing components in place",
                            List.of("reuses validation, rate limiting and persistence", "smallest change surface"),
                            List.of("touches shared code paths; needs regression tests"), true),
                    new Alternative("Build a separate service for the new capability",
                            List.of("independent deployment"),
                            List.of("duplicates validation and rate limiting", "new operational burden"), false));
        }
        return List.of(
                new Alternative("Layered Spring Boot service with a relational database",
                        List.of("well understood", "transactional consistency", "simple operations"),
                        List.of("single node needs a distributed rate limiter to scale out"), true),
                new Alternative("Serverless functions with a key-value store",
                        List.of("scales to zero"),
                        List.of("cold starts hurt redirect latency", "harder analytics queries"), false));
    }

    private static List<Decision> decisions(List<String> tokens, List<ApiChange> apiChanges, boolean existing) {
        List<Decision> decisions = new ArrayList<>();
        if (apiChanges.stream().anyMatch(a -> a.path().equals("/{code}"))) {
            decisions.add(new Decision("Redirect status code", "Use HTTP 302 for redirects",
                    "Clients do not cache permanently, so expiry, deactivation and analytics stay effective"));
        }
        if (!existing && apiChanges.stream().anyMatch(a -> a.path().equals("/api/v1/links"))) {
            decisions.add(new Decision("Short code generation", "Random Base62 codes from SecureRandom",
                    "Unpredictable codes resist enumeration; collisions are retried"));
        }
        if (Text.anyToken(tokens, List.of("click", "clicks", "analytic*"))) {
            decisions.add(new Decision("Click recording", "Record clicks asynchronously",
                    "Keeps the redirect path fast; analytics are eventually consistent"));
        }
        if (Text.anyToken(tokens, List.of("bulk", "batch"))) {
            decisions.add(new Decision("Bulk semantics", "Per-item results with partial-failure semantics",
                    "One invalid URL must not fail the whole batch; each item reports its own status"));
        }
        decisions.add(existing
                ? new Decision("Reuse of existing controls", "Reuse the existing URL validation, alias rules and rate "
                + "limiter", "Consistent behaviour across endpoints and no duplicated security logic")
                : new Decision("Architecture style", "Layered architecture (controller, service, repository)",
                "Clear responsibilities and testable layers"));
        return decisions;
    }

    private static RiskAssessment risk(RequirementSpec spec, List<ApiChange> apiChanges,
                                       List<DataModelChange> dataChanges, List<String> deferred) {
        RiskAssessment risk = spec.preliminaryRisk() == null ? RiskAssessment.low() : spec.preliminaryRisk();
        List<String> factors = new ArrayList<>();
        RiskLevel level = RiskLevel.LOW;
        if (apiChanges.stream().anyMatch(ApiChange::breaking)
                || dataChanges.stream().anyMatch(d -> !d.backwardCompatible())) {
            level = RiskLevel.HIGH;
            factors.add("breaking or backward-incompatible change");
        }
        if (!apiChanges.isEmpty()) {
            level = RiskLevel.max(level, RiskLevel.MEDIUM);
            factors.add("public API change (" + apiChanges.size() + " endpoints)");
        }
        if (!dataChanges.isEmpty()) {
            level = RiskLevel.max(level, RiskLevel.MEDIUM);
            factors.add("data model change (" + String.join(", ",
                    dataChanges.stream().map(DataModelChange::entity).distinct().toList()) + ")");
        }
        if (!deferred.isEmpty()) {
            factors.add(deferred.size() + " lower-confidence components left unchanged ("
                    + String.join(", ", deferred.stream().limit(5).toList()) + (deferred.size() > 5 ? ", ..." : "")
                    + ")");
        }
        return risk.merge(new RiskAssessment(level, factors));
    }
}
