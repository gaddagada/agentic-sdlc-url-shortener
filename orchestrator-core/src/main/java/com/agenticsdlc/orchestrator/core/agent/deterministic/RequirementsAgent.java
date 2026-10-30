package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.ClarificationGate;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AcceptanceCriterion;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AmbiguityFinding;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.AmbiguityReport;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Category;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Classification;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Priority;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec.Requirement;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

/**
 * Deterministic requirements analyst ({@code intake}): normalizes the requirement, scores ambiguity,
 * classifies it, extracts functional and non-functional requirements with acceptance criteria and assesses
 * preliminary risk. When the ambiguity score reaches the threshold and no clarification answers exist, it
 * asks clarification questions instead of producing a specification.
 *
 * <p>Refinements over the minimal algorithm (all deterministic): clauses after a colon are split into
 * separate requirements; imperative clauses (starting with an action verb) are requirements with MUST
 * priority; sentences that are neither are kept as COULD requirements so that nothing is lost; clarification
 * answers become MUST requirements.</p>
 */
public final class RequirementsAgent implements StageAgent {

    static final List<String> VAGUE = List.of("better", "faster", "improve", "improved", "enhance", "optimize",
            "robust", "reliable", "scalable", "modern", "clean", "nicer", "performant");
    static final List<String> UNITS = List.of("ms", "seconds", "rps", "percent", "users");
    static final List<String> DOMAIN_NOUNS = List.of("api", "endpoint", "service", "link", "url", "redirect",
            "analytics", "click", "database", "schema", "cache", "latency", "throughput", "alias", "expiry", "ttl",
            "test", "documentation", "deploy");
    static final List<String> OPEN_ENDED = List.of("etc", "and so on", "somehow", "whatever", "as needed");
    static final List<String> UNSPECIFIED_SCOPE = List.of("everything", "all the", "entire system");
    static final List<String> TRIGGERS = List.of("must", "should", "shall", "need", "needs", "support", "supports",
            "provide", "provides", "allow", "allows", "expose", "exposes", "record", "records", "prevent",
            "prevents");
    static final List<String> ACTION_VERBS = List.of("create", "redirect", "record", "expose", "accept", "return",
            "reuse", "respect", "support", "provide", "allow", "prevent", "store", "track", "list", "delete",
            "validate", "generate", "send", "notify", "cache", "log", "add", "build", "implement", "enable", "make",
            "keep", "show", "display", "update", "remove", "limit", "expire", "shorten", "count", "aggregate",
            "compute", "measure", "monitor", "report", "retain", "hash", "encrypt", "protect", "deactivate",
            "disable", "serve", "reject", "block", "introduce", "extend", "migrate", "refactor", "ensure");
    static final List<String> BROWNFIELD_WORDS = List.of("existing", "current", "add to", "extend", "migrate",
            "refactor", "legacy", "bulk", "the shortener");
    private static final List<String> MUST_WORDS = List.of("must", "shall", "required");
    private static final List<String> UMBRELLA_WORDS = List.of("service", "system", "application", "platform");
    private static final List<String> PRESERVATION = List.of("unchanged", "keep working", "keeps working",
            "continue to work", "backward compatible", "must not change", "must not break");

    private static final List<Map.Entry<Category, List<String>>> CATEGORIES = List.of(
            Map.entry(Category.SECURITY, List.of("private", "ssrf", "auth*", "validat*", "secur*", "injection", "xss",
                    "encrypt*", "ratelimit*", "throttl*")),
            Map.entry(Category.COMPLIANCE, List.of("pii", "retention", "audit*", "gdpr", "compliance", "privacy")),
            Map.entry(Category.PERFORMANCE, List.of("latency", "rps", "throughput", "p95", "p99", "p50", "fast",
                    "faster", "speed", "performance", "performant")),
            Map.entry(Category.RELIABILITY, List.of("availab*", "uptime", "downtime", "retry", "retries", "failover",
                    "fault*", "resilien*", "reliab*", "durab*", "recover*")),
            Map.entry(Category.OPERABILITY, List.of("health", "metric*", "log", "logs", "logging", "monitor*", "alert*",
                    "observab*", "tracing")),
            Map.entry(Category.USABILITY, List.of("usab*", "ui", "ux", "accessib*")));
    private static final List<String> SECURITY_RISK = List.of("private", "ssrf", "auth*", "secur*", "pii",
            "personal", "retention", "gdpr", "password*", "encrypt*", "validat*", "compliance", "privacy");
    private static final List<String> SCHEMA_RISK = List.of("schema", "table", "column*", "migration*", "database",
            "persist*", "store", "stored", "storage");
    private static final List<String> API_RISK = List.of("api", "apis", "endpoint*");
    private static final List<String> PERFORMANCE_RISK = List.of("latency", "rps", "throughput", "p95", "p99");

    /**
     * Creates the agent.
     */
    public RequirementsAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.REQUIREMENTS;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) {
        RequirementSpec spec = analyze(context.requirement(), context.clarificationQuestions(),
                context.clarificationAnswers(), context.workspaceIndex(), context.options().workspace(),
                context.settings().ambiguityThreshold());
        Optional<List<ClarificationQuestion>> questions = ClarificationGate.questionsIfNeeded(spec, context);
        if (questions.isPresent()) {
            return new StageResult.NeedsClarification(questions.get(), String.format(Locale.ROOT,
                    "ambiguity score %.2f reaches the threshold %.2f (%s); clarification is required before planning",
                    spec.ambiguity().score(), context.settings().ambiguityThreshold(),
                    String.join(", ", spec.ambiguity().findings().stream().map(AmbiguityFinding::code).toList())),
                    null);
        }
        return StageResult.success(spec, String.format(Locale.ROOT,
                "classified as %s with %d functional and %d non-functional requirements; ambiguity %.2f; "
                        + "preliminary risk %s", spec.classification(), spec.functional().size(),
                spec.nonFunctional().size(), spec.ambiguity().score(), spec.preliminaryRisk().level()));
    }

    /**
     * Analyses a requirement.
     *
     * @param requirement the active requirement
     * @param questions answered clarification questions (may be empty)
     * @param answers clarification answers by question id (may be empty)
     * @param index workspace index or null
     * @param workspace workspace mode
     * @param threshold ambiguity threshold
     * @return the specification
     */
    public RequirementSpec analyze(String requirement, List<ClarificationQuestion> questions,
                                   Map<String, String> answers, @Nullable CodebaseIndex index, WorkspaceMode workspace,
                                   double threshold) {
        String original = Text.normalizeWhitespace(requirement);
        List<String> answerTexts = orderedAnswers(questions, answers);
        String statement = answerTexts.isEmpty() ? original
                : original + " Clarifications: " + String.join("; ", answerTexts) + ".";
        AmbiguityReport ambiguity = ambiguity(statement);
        Classification classification = classify(original, ambiguity.score(), threshold, index, workspace);

        List<Requirement> functional = new ArrayList<>();
        List<Requirement> nonFunctional = new ArrayList<>();
        for (Candidate candidate : candidates(original, answerTexts)) {
            List<String> tokens = Text.tokens(candidate.text());
            Category category = category(tokens, candidate.text());
            boolean measurable = Text.hasDigit(candidate.text());
            if (category == null) {
                functional.add(new Requirement("FR-" + (functional.size() + 1), candidate.text(),
                        candidate.priority(), Category.FUNCTIONAL, measurable));
            } else {
                nonFunctional.add(new Requirement("NFR-" + (nonFunctional.size() + 1), candidate.text(),
                        candidate.priority(), category, measurable));
            }
        }
        if (functional.isEmpty()) {
            functional.add(new Requirement("FR-1", Text.stripTrailingPunctuation(original), Priority.MUST,
                    Category.FUNCTIONAL, Text.hasDigit(original)));
        }
        List<AcceptanceCriterion> criteria = acceptanceCriteria(functional, nonFunctional);
        List<String> assumptions = new ArrayList<>(List.of("Single-region deployment",
                "HTTP JSON APIs consumed by API clients", "Java 25 and Spring Boot stack",
                "Existing CI pipeline runs ./mvnw verify"));
        if (!answerTexts.isEmpty()) {
            assumptions.add("Clarification answers are authoritative");
        }
        List<String> openQuestions = answerTexts.isEmpty() && ambiguity.score() >= threshold
                ? ambiguity.questions().stream().map(ClarificationQuestion::question).toList() : List.of();
        return new RequirementSpec(statement, intent(original), classification, scopeIn(statement),
                scopeOut(classification), functional, nonFunctional, criteria, assumptions, openQuestions, ambiguity,
                preliminaryRisk(statement));
    }

    private static List<String> orderedAnswers(List<ClarificationQuestion> questions, Map<String, String> answers) {
        List<String> ordered = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (ClarificationQuestion q : questions) {
            String answer = answers.get(q.id());
            if (answer != null && !answer.isBlank()) {
                ordered.add(Text.stripTrailingPunctuation(Text.normalizeWhitespace(answer)));
                used.add(q.id());
            }
        }
        answers.keySet().stream().filter(k -> !used.contains(k)).sorted().forEach(k -> {
            String answer = answers.get(k);
            if (answer != null && !answer.isBlank()) {
                ordered.add(Text.stripTrailingPunctuation(Text.normalizeWhitespace(answer)));
            }
        });
        return ordered;
    }

    /**
     * Scores ambiguity (weights sum above 1, clamped to 1.0) and derives one question per fired finding.
     *
     * @param statement the (normalized) statement
     * @return the report
     */
    static AmbiguityReport ambiguity(String statement) {
        String lower = statement.toLowerCase(Locale.ROOT);
        List<String> tokens = Text.tokens(statement);
        boolean digits = Text.hasDigit(statement);
        List<AmbiguityFinding> findings = new ArrayList<>();
        List<ClarificationQuestion> questions = new ArrayList<>();
        if (statement.length() < 60) {
            add(findings, questions, "SHORT_STATEMENT", "the statement has fewer than 60 characters", 0.25,
                    "Can you describe the expected behaviour in more detail (inputs, outputs and who uses it)?",
                    "A very short statement leaves scope and intent open.",
                    List.of("Operators need faster redirects for links shared in campaigns",
                            "API clients need predictable error responses"));
        }
        String vague = Text.firstMatch(tokens, VAGUE);
        if (vague != null && !digits) {
            add(findings, questions, "VAGUE_QUALIFIER_WITHOUT_METRIC", "vague qualifier '" + vague
                            + "' without any number", 0.30,
                    "Which operations must be faster (redirect, create, analytics) and what is the target p95 latency?",
                    "Qualifiers like '" + vague + "' cannot be verified without a target.",
                    List.of("Redirect p95 under 50 ms at 500 rps", "Link creation p95 under 200 ms"));
        }
        boolean units = Text.anyToken(tokens, UNITS) || statement.contains("%");
        if (!digits && !units) {
            add(findings, questions, "NO_MEASURABLE_TARGET", "no number or unit defines success", 0.15,
                    "What measurable targets define done (for example availability %, error rate, p95 latency)?",
                    "Without a measurable target the change cannot be verified or released safely.",
                    List.of("99.9% monthly availability for redirects", "Error rate below 0.1% of requests"));
        }
        boolean domainNoun = tokens.stream().anyMatch(t -> DOMAIN_NOUNS.stream()
                .anyMatch(n -> t.equals(n) || t.equals(n + "s") || t.equals(n + "es")));
        if (!domainNoun) {
            add(findings, questions, "NO_DOMAIN_NOUN", "no domain object (link, redirect, analytics, API, ...) is named",
                    0.20,
                    "Which part of the system is affected (links API, redirects, analytics, persistence or operations)?",
                    "The affected components determine impact, risk and tests.",
                    List.of("The redirect path and link lookup", "Click analytics recording"));
        }
        if (Text.containsPhrase(statement, OPEN_ENDED)) {
            add(findings, questions, "OPEN_ENDED_PHRASE", "open-ended phrase (etc, somehow, as needed, ...)", 0.10,
                    "Please list the complete set of expected behaviours instead of open-ended phrases.",
                    "Open-ended scope cannot be planned or verified.",
                    List.of("Only the behaviours listed above are in scope"));
        }
        boolean conflict = (lower.contains("no database") && (lower.contains("persist") || lower.contains("store")))
                || (tokens.contains("synchronous") && tokens.stream().anyMatch(t -> t.startsWith("async")));
        if (conflict) {
            add(findings, questions, "CONFLICTING_TERMS", "the statement contains conflicting terms", 0.20,
                    "The requirement contains conflicting terms (storage or synchronicity). Which one applies?",
                    "Conflicting constraints lead to an unimplementable design.",
                    List.of("Persist data in the relational database", "Process asynchronously"));
        }
        if (Text.containsPhrase(statement, UNSPECIFIED_SCOPE)) {
            add(findings, questions, "UNSPECIFIED_SCOPE", "unbounded scope (everything, all the, entire system)", 0.10,
                    "Which components are explicitly in scope and which are out of scope?",
                    "Unbounded scope prevents a reliable plan.",
                    List.of("Only the link and redirect APIs are in scope"));
        }
        double sum = findings.stream().mapToDouble(AmbiguityFinding::weight).sum();
        double score = Math.round(Math.min(1.0, sum) * 100.0) / 100.0;
        return new AmbiguityReport(score, findings, questions);
    }

    private static void add(List<AmbiguityFinding> findings, List<ClarificationQuestion> questions, String code,
                            String description, double weight, String question, String why, List<String> suggested) {
        findings.add(new AmbiguityFinding(code, description, weight));
        questions.add(new ClarificationQuestion("q" + (questions.size() + 1), question, why, suggested));
    }

    private static Classification classify(String original, double score, double threshold,
                                           @Nullable CodebaseIndex index, WorkspaceMode workspace) {
        if (score >= threshold) {
            return Classification.AMBIGUOUS;
        }
        String lower = original.toLowerCase(Locale.ROOT);
        if (BROWNFIELD_WORDS.stream().anyMatch(lower::contains)) {
            return Classification.BROWNFIELD;
        }
        boolean newSystem = lower.matches("^(build|create|implement)\\s+(a\\s+)?new\\b.*");
        if (index != null && !index.isEmpty() && workspace == WorkspaceMode.REPO && !newSystem) {
            return Classification.BROWNFIELD;
        }
        return Classification.GREENFIELD;
    }

    private record Candidate(String text, Priority priority) {
    }

    private static List<Candidate> candidates(String original, List<String> answers) {
        List<Candidate> candidates = new ArrayList<>();
        for (String sentence : Text.sentences(original)) {
            Priority sentencePriority = priority(Text.tokens(sentence));
            int colon = sentence.indexOf(':');
            List<String> clauses = new ArrayList<>();
            if (colon > 0 && colon < sentence.length() - 1) {
                String head = sentence.substring(0, colon);
                List<String> headTokens = Text.tokens(head);
                boolean umbrella = Text.anyToken(headTokens, UMBRELLA_WORDS);
                if (!headTokens.isEmpty() && ACTION_VERBS.contains(headTokens.getFirst()) && !umbrella) {
                    clauses.add(head);
                }
                clauses.addAll(splitClauses(sentence.substring(colon + 1)));
            } else {
                clauses.add(sentence);
            }
            for (String clause : clauses) {
                String text = Text.capitalize(Text.stripTrailingPunctuation(clause));
                if (text.isBlank()) {
                    continue;
                }
                List<String> tokens = Text.tokens(text);
                boolean trigger = Text.anyToken(tokens, TRIGGERS);
                boolean imperative = !tokens.isEmpty() && ACTION_VERBS.contains(tokens.getFirst());
                Priority priority;
                if (sentencePriority != Priority.COULD) {
                    priority = sentencePriority;
                } else if (imperative || trigger) {
                    priority = Priority.MUST;
                } else {
                    priority = Priority.COULD;
                }
                candidates.add(new Candidate(text, priority));
            }
        }
        for (String answer : answers) {
            candidates.add(new Candidate(Text.capitalize(answer), Priority.MUST));
        }
        return candidates;
    }

    private static Priority priority(List<String> tokens) {
        if (Text.anyToken(tokens, MUST_WORDS)) {
            return Priority.MUST;
        }
        if (tokens.contains("should")) {
            return Priority.SHOULD;
        }
        return Priority.COULD;
    }

    /** Splits a clause list on commas/semicolons outside parentheses and on " and " before an action verb. */
    static List<String> splitClauses(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if ((c == ',' || c == ';') && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.strip();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("and ")) {
                trimmed = trimmed.substring(4).strip();
            }
            clauses.addAll(splitOnAndVerb(trimmed));
        }
        return clauses.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> splitOnAndVerb(String clause) {
        List<String> result = new ArrayList<>();
        String rest = clause;
        while (true) {
            int split = -1;
            String lower = rest.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int idx = lower.indexOf(" and ", from);
                if (idx < 0) {
                    break;
                }
                String after = lower.substring(idx + 5).strip();
                String next = after.split("[^a-z]+", 2)[0];
                if (ACTION_VERBS.contains(next) && !insideParentheses(rest, idx)) {
                    split = idx;
                    break;
                }
                from = idx + 5;
            }
            if (split < 0) {
                result.add(rest);
                return result;
            }
            result.add(rest.substring(0, split));
            rest = rest.substring(split + 5);
        }
    }

    private static boolean insideParentheses(String text, int index) {
        int depth = 0;
        for (int i = 0; i < index; i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth > 0;
    }

    private static @Nullable Category category(List<String> tokens, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<Category, List<String>> entry : CATEGORIES) {
            if (Text.anyToken(tokens, entry.getValue())) {
                return entry.getKey();
            }
            if (entry.getKey() == Category.SECURITY && lower.contains("rate limit")) {
                return Category.SECURITY;
            }
            if (entry.getKey() == Category.PERFORMANCE && (lower.contains("response time")
                    || lower.contains("requests per second"))) {
                return Category.PERFORMANCE;
            }
            if (entry.getKey() == Category.COMPLIANCE && lower.contains("personal data")) {
                return Category.COMPLIANCE;
            }
        }
        return null;
    }

    private static List<AcceptanceCriterion> acceptanceCriteria(List<Requirement> functional,
                                                                List<Requirement> nonFunctional) {
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (Requirement fr : functional) {
            List<String> tokens = Text.tokens(fr.text());
            String verb = tokens.isEmpty() ? "" : tokens.getFirst();
            String[] template = template(verb, fr.id());
            criteria.add(new AcceptanceCriterion("AC-" + (criteria.size() + 1), template[0],
                    "the client exercises: " + Text.decapitalize(fr.text()), template[1], List.of(fr.id())));
        }
        for (Requirement nfr : nonFunctional) {
            if (nfr.measurable()) {
                criteria.add(new AcceptanceCriterion("AC-" + (criteria.size() + 1),
                        "a production-like environment under the stated load",
                        "the scenario is measured: " + Text.decapitalize(nfr.text()),
                        "the measured value meets the target of " + nfr.id(), List.of(nfr.id())));
            }
        }
        return criteria;
    }

    private static String[] template(String verb, String requirementId) {
        return switch (verb) {
            case "create", "add", "shorten", "generate", "accept", "submit", "build", "implement", "introduce" ->
                    new String[] {"a valid request from an API client",
                            "the resource is created and returned with its identifier"};
            case "redirect", "resolve", "serve" -> new String[] {"an existing active short link",
                    "the client receives the expected redirect response"};
            case "record", "track", "count", "aggregate", "measure" -> new String[] {
                    "a link that receives traffic", "the events are recorded and visible through the analytics API"};
            case "expose", "provide", "show", "display", "list", "return", "report" -> new String[] {
                    "the service is running", "the requested information is available through the documented "
                    + "endpoint"};
            case "delete", "remove", "deactivate", "disable" -> new String[] {"an existing resource",
                    "the resource is no longer available and further access is rejected"};
            case "reuse", "respect", "keep", "preserve", "existing" -> new String[] {
                    "the existing behaviour and configuration", "the existing rules apply unchanged"};
            case "prevent", "reject", "validate", "block", "limit", "protect" -> new String[] {
                    "a request that violates the rule", "the request is rejected with a clear error"};
            default -> new String[] {"the system in its normal operating state",
                    "the behaviour described in " + requirementId + " is observable and covered by an automated test"};
        };
    }

    private static String intent(String original) {
        String first = Text.sentences(original).stream().findFirst().orElse(original);
        int colon = first.indexOf(':');
        String head = colon > 0 ? first.substring(0, colon) : first;
        return Text.shorten(Text.stripTrailingPunctuation(head), 160);
    }

    private static List<String> scopeIn(String fullStatement) {
        StringBuilder changing = new StringBuilder();
        for (String sentence : Text.sentences(fullStatement)) {
            String l = sentence.toLowerCase(Locale.ROOT);
            if (PRESERVATION.stream().noneMatch(l::contains)) {
                changing.append(sentence).append(' ');
            }
        }
        String statement = changing.toString();
        List<String> tokens = Text.tokens(statement);
        String lower = statement.toLowerCase(Locale.ROOT);
        List<String> scope = new ArrayList<>();
        if (Text.anyToken(tokens, List.of("bulk", "batch"))) {
            scope.add("Bulk link creation");
        }
        if (Text.anyToken(tokens, List.of("link", "links", "url", "urls", "shorten*"))) {
            scope.add("Link management API");
        }
        if (Text.anyToken(tokens, List.of("redirect*"))) {
            scope.add("Redirect handling");
        }
        if (Text.anyToken(tokens, List.of("click*", "analytic*", "referrer*"))) {
            scope.add("Click analytics");
        }
        if (Text.anyToken(tokens, List.of("alias*"))) {
            scope.add("Custom aliases");
        }
        if (Text.anyToken(tokens, List.of("expir*", "ttl"))) {
            scope.add("Link expiry");
        }
        if (lower.contains("rate limit") || Text.anyToken(tokens, List.of("throttl*"))) {
            scope.add("Rate limiting");
        }
        if (Text.anyToken(tokens, List.of("private", "ssrf", "validat*"))) {
            scope.add("URL validation and SSRF protection");
        }
        if (Text.anyToken(tokens, List.of("health", "metric*", "monitor*"))) {
            scope.add("Operational endpoints (health, metrics)");
        }
        if (Text.anyToken(tokens, List.of("fast", "faster", "latency", "p95", "rps", "performance", "throughput"))) {
            scope.add("Performance of the request path");
        }
        if (Text.anyToken(tokens, List.of("reliab*", "availab*", "uptime", "resilien*"))) {
            scope.add("Reliability controls");
        }
        if (scope.isEmpty()) {
            scope.add("Behaviour described in the requirement");
        }
        return scope;
    }

    private static List<String> scopeOut(Classification classification) {
        List<String> out = new ArrayList<>(List.of("User accounts and end-user authentication (not requested)",
                "Multi-region deployment", "User interface beyond the HTTP API"));
        if (classification == Classification.BROWNFIELD) {
            out.add("Behaviour changes to unrelated existing endpoints");
        }
        return out;
    }

    private static RiskAssessment preliminaryRisk(String statement) {
        List<String> tokens = Text.tokens(statement);
        String lower = statement.toLowerCase(Locale.ROOT);
        List<String> factors = new ArrayList<>();
        RiskLevel level = RiskLevel.LOW;
        String security = Text.firstMatch(tokens, SECURITY_RISK);
        if (security != null || lower.contains("ip address") || lower.contains("personal data")) {
            level = RiskLevel.HIGH;
            factors.add("security or compliance sensitive behaviour ("
                    + (security != null ? security : "personal data") + ")");
        }
        String schema = Text.firstMatch(tokens, SCHEMA_RISK);
        if (schema != null) {
            level = RiskLevel.HIGH;
            factors.add("persistent data or schema change (" + schema + ")");
        }
        String api = Text.firstMatch(tokens, API_RISK);
        if (api != null) {
            level = RiskLevel.max(level, RiskLevel.MEDIUM);
            factors.add("public API change (" + api + ")");
        }
        String performance = Text.firstMatch(tokens, PERFORMANCE_RISK);
        if (performance != null) {
            level = RiskLevel.max(level, RiskLevel.MEDIUM);
            factors.add("performance target (" + performance + ")");
        }
        return new RiskAssessment(level, factors);
    }
}
