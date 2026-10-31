package com.agenticsdlc.orchestrator.core.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.VerificationReport;
import com.agenticsdlc.orchestrator.core.graph.StageDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.Approval;
import com.agenticsdlc.orchestrator.core.model.ApprovalStatus;
import com.agenticsdlc.orchestrator.core.model.Clarification;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.util.RepoPaths;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.ImpactAnalyzer;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources;
import com.agenticsdlc.orchestrator.core.workspace.IndexLookup;
import com.agenticsdlc.orchestrator.core.workspace.IndexedType;
import com.agenticsdlc.orchestrator.core.workspace.WorkspaceSandbox;

import tools.jackson.core.JacksonException;

/**
 * Builds stage contexts (under the run lock) and executes agent attempts (off the lock) with a timeout,
 * mapping exceptions to classified {@link StageFailure}s.
 */
final class StageExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StageExecutor.class);

    /** Roles whose context carries the source of impacted files (REPO runs with a workspace root). */
    static final Set<AgentRole> SOURCE_ROLES = EnumSet.of(AgentRole.IMPLEMENTATION, AgentRole.VERIFICATION,
            AgentRole.SECURITY_REVIEW);

    /** Repository-relative file paths mentioned in feedback or errors (at least one directory and an extension). */
    private static final Pattern MENTIONED_PATH = Pattern.compile(
            "(?<![\\w./-])((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.[A-Za-z0-9]{1,10})(?![\\w/])");

    /**
     * Start of the line the engine appends to the last error of an attempt whose failure named requested sources
     * ({@link StageFailure#requestedSources()}); the next attempt's source selection reads the paths back from it.
     */
    static final String REQUESTED_SOURCES_PREFIX = "Sources requested by the failed attempt (shown in the next "
            + "attempt): ";

    /** Maximum requested sources taken from one change set or failure. */
    static final int MAX_REQUESTED_SOURCES = 12;

    /** Maximum extra files requested because feedback, check details or the previous error name their types. */
    static final int MAX_IDENTIFIER_FILES = 12;

    /** Minimum confidence of a static impact component whose source is shown with MODIFY priority. */
    static final double STATIC_SOURCE_CONFIDENCE = 0.8;

    /** Confidence given to explicitly named files (change set targets, feedback mentions, requested sources). */
    private static final double EXPLICIT = 1.0;

    /** Confidence given to the existing components of the accepted design. */
    private static final double DESIGN_COMPONENT = 0.8;

    /** Maximum runs whose static impact analysis is cached. */
    private static final int STATIC_IMPACT_CACHE_SIZE = 256;

    /**
     * Outcome of an attempt.
     *
     * @param result the result, or null for a {@link #leaseLost() LEASE_LOST} outcome
     * @param latencyMs agent latency
     * @param context the context the agent was given
     * @param leaseLost LEASE_LOST: the heartbeat found that this node lost the run's lease while the agent ran; the
     *                  agent was interrupted and the attempt must be treated like an interruption (nothing is applied
     *                  or retried on this node)
     */
    record AttemptOutcome(@Nullable StageResult result, long latencyMs, StageContext context, boolean leaseLost) {

        AttemptOutcome(StageResult result, long latencyMs, StageContext context) {
            this(result, latencyMs, context, false);
        }

        static AttemptOutcome leaseLost(StageContext context, long latencyMs) {
            return new AttemptOutcome(null, latencyMs, context, true);
        }
    }

    /**
     * A context together with the paths of the impacted sources it shows, in reading order (for the STAGE_STARTED
     * audit event).
     *
     * @param context the context
     * @param shownSources paths of {@link StageContext#impactedSources()} in reading order (priority, confidence)
     */
    record PreparedContext(StageContext context, List<String> shownSources) {

        PreparedContext {
            shownSources = List.copyOf(shownSources);
        }
    }

    /**
     * The static impact analysis of a run, valid for one plan version, one requirement specification artifact and one
     * index (identified by its root name, index time, size and identity hash, so that no index is retained).
     */
    private record StaticImpact(int planVersion, String specArtifactId, String indexKey,
                                @Nullable ImpactAnalysis analysis) {
    }

    private final WorkflowGraph graph;
    private final EngineConfig config;
    private final AgentSettings settings;
    private final ExecutorService executor;
    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();
    private final Map<String, StaticImpact> staticImpacts = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StaticImpact> eldest) {
                    return size() > STATIC_IMPACT_CACHE_SIZE;
                }
            });

    StageExecutor(WorkflowGraph graph, EngineConfig config, ExecutorService executor) {
        this.graph = graph;
        this.config = config;
        this.settings = config.agentSettings();
        this.executor = executor;
    }

    /**
     * Builds the immutable context of an attempt ({@link #prepare}).
     */
    StageContext buildContext(Run run, StageDefinition def, StageExecution exec) {
        return prepare(run, def, exec).context();
    }

    /**
     * Builds the immutable context of an attempt: active requirement, clarification answers, accepted
     * artifacts of all transitive predecessors, feedback, attempt, plan version, previous error, workspace
     * index (REPO runs), options, approvals granted in the current plan, agent settings, the stage's direct
     * predecessors (LLM prompts embed only their artifacts and the requirement specification in full), the static
     * impact analysis of the accepted requirement specification over the index (REPO runs with a non-empty index,
     * stages downstream of the specification; {@link #staticImpact}) and, for the implementation, verification and
     * security review roles of REPO runs with a workspace root, the bounded source of the impacted files
     * ({@link #impactedSourceRequests}).
     */
    PreparedContext prepare(Run run, StageDefinition def, StageExecution exec) {
        Map<String, Artifact> upstream = new LinkedHashMap<>();
        for (String up : graph.upstreamOf(def.id())) {
            run.acceptedArtifact(up).ifPresent(a -> upstream.put(up, a));
        }
        Clarification clarification = run.clarification();
        Map<String, String> answers = clarification != null && clarification.answered() ? clarification.answers()
                : Map.of();
        List<ClarificationQuestion> questions = clarification != null && clarification.answered()
                ? clarification.questions() : List.of();
        List<Approval> approvals = run.approvals().stream()
                .filter(a -> a.status() == ApprovalStatus.APPROVED && a.planVersion() == run.planVersion())
                .toList();
        String previousError = exec.attempts() > 1 ? exec.lastError() : null;
        CodebaseIndex index = run.options().workspace() == WorkspaceMode.REPO ? workspaceIndex() : null;
        ImpactAnalysis staticImpact = staticImpact(run, upstream, index);
        StageContext context = new StageContext(run.id(), def.id(), def.role(), run.activeRequirement(),
                run.requirement(), answers, questions, upstream, exec.feedback(), exec.attempts(), run.planVersion(),
                previousError, index, run.options(), approvals, settings, graph.predecessors(def.id()), Map.of(),
                List.of(), staticImpact);
        if (run.options().workspace() != WorkspaceMode.REPO || config.workspaceRoot() == null
                || !SOURCE_ROLES.contains(def.role())) {
            return new PreparedContext(context, List.of());
        }
        List<ImpactedSources.Request> requests = impactedSourceRequests(run, exec.feedback(), previousError, index,
                staticImpact);
        if (requests.isEmpty()) {
            return new PreparedContext(context, List.of());
        }
        try {
            ImpactedSources.Result sources = ImpactedSources.read(config.workspaceRoot(), requests,
                    config.sourceContextPerFileChars(), config.sourceContextTotalChars());
            LOG.debug("impacted sources runId={} stageId={} files={} chars={} notes={}", run.id(), def.id(),
                    sources.sources().size(), sources.totalChars(), sources.notes().size());
            return new PreparedContext(context.withImpactedSources(sources.sources(), sources.notes()),
                    sources.order());
        } catch (RuntimeException e) {
            LOG.warn("impacted sources unavailable runId={} stageId={} error={}", run.id(), def.id(), e.toString());
            return new PreparedContext(context.withImpactedSources(Map.of(), List.of("impacted sources unavailable: "
                    + e.getClass().getSimpleName())), List.of());
        }
    }

    /**
     * Returns the deterministic static impact analysis ({@link ImpactAnalyzer}) of the accepted requirement
     * specification among the stage's upstream artifacts over the index, computed once per run, plan version,
     * specification artifact and index (a re-plan, a clarification that produces a new specification and a re-indexed
     * workspace each cause a recomputation). Null without a non-empty index or an upstream specification, or when the
     * analysis fails.
     *
     * @param run the run
     * @param upstream the stage's upstream artifacts
     * @param index the workspace index of a REPO run, or null
     * @return the analysis, or null
     */
    @Nullable ImpactAnalysis staticImpact(Run run, Map<String, Artifact> upstream, @Nullable CodebaseIndex index) {
        if (index == null || index.isEmpty()) {
            return null;
        }
        Artifact spec = upstream.values().stream().filter(a -> a.payload() instanceof RequirementSpec).findFirst()
                .orElse(null);
        if (spec == null) {
            return null;
        }
        String indexKey = index.rootName() + "|" + index.indexedAt() + "|" + index.indexedFiles() + "|"
                + System.identityHashCode(index);
        StaticImpact cached = staticImpacts.get(run.id());
        if (cached != null && cached.planVersion() == run.planVersion() && cached.specArtifactId().equals(spec.id())
                && cached.indexKey().equals(indexKey)) {
            return cached.analysis();
        }
        ImpactAnalysis analysis;
        try {
            analysis = analyzer.analyze(index, (RequirementSpec) spec.payload());
            LOG.debug("static impact runId={} planVersion={} spec={} components={}", run.id(), run.planVersion(),
                    spec.reference(), analysis.components().size());
        } catch (RuntimeException e) {
            LOG.warn("static impact analysis failed runId={} error={}", run.id(), e.toString());
            analysis = null;
        }
        staticImpacts.put(run.id(), new StaticImpact(run.planVersion(), spec.id(), indexKey, analysis));
        return analysis;
    }

    /**
     * Returns the files whose source an implementation, verification or security review attempt sees
     * ({@link #impactedSourceRequests(Run, List, String, CodebaseIndex, ImpactAnalysis)} without a static analysis).
     *
     * @param run the run
     * @param feedback the stage's feedback
     * @param previousError the previous attempt's error, or null
     * @param index the codebase index, or null
     * @return the requests, one per path at its highest priority
     */
    static List<ImpactedSources.Request> impactedSourceRequests(Run run, List<String> feedback,
                                                               @Nullable String previousError,
                                                               @Nullable CodebaseIndex index) {
        return impactedSourceRequests(run, feedback, previousError, index, null);
    }

    /**
     * Returns the files whose source an implementation, verification or security review attempt sees, ordered by
     * priority and, within a priority, by confidence (explicitly named files count as 1.0):
     *
     * <ol>
     *   <li>MODIFY: the MODIFY changes of the accepted and of the latest change set; the {@code requestedSources} of
     *       those change sets and the sources a failed attempt requested ({@link #REQUESTED_SOURCES_PREFIX} line of
     *       the previous error), at most {@value #MAX_REQUESTED_SOURCES}; files named by the stage feedback, the
     *       failed blocking checks of the latest failed verification report (when there is feedback) and the previous
     *       error, as repository paths, {@code *.java} file names or type names ({@link IndexLookup#mentionedPaths},
     *       at most {@value #MAX_IDENTIFIER_FILES} files beyond the others); MODIFY components of the accepted impact
     *       analysis; MODIFY and EXTEND components of the static impact analysis with confidence of at least
     *       {@value #STATIC_SOURCE_CONFIDENCE}; DELETE changes;</li>
     *   <li>EXTEND: existing components of the accepted design and EXTEND components of the accepted impact
     *       analysis;</li>
     *   <li>REGRESSION_RISK: REGRESSION_RISK components of the accepted impact analysis (read only when the budget
     *       still allows).</li>
     * </ol>
     *
     * <p>Paths and names the codebase index does not list are resolved by path suffix, file name or type name
     * ({@link IndexLookup}).</p>
     *
     * @param run the run
     * @param feedback the stage's feedback
     * @param previousError the previous attempt's error, or null
     * @param index the codebase index, or null
     * @param staticImpact the static impact analysis of the run, or null
     * @return the requests, one per path at its highest priority
     */
    static List<ImpactedSources.Request> impactedSourceRequests(Run run, List<String> feedback,
                                                               @Nullable String previousError,
                                                               @Nullable CodebaseIndex index,
                                                               @Nullable ImpactAnalysis staticImpact) {
        List<ChangeSet> changeSets = new ArrayList<>();
        run.acceptedPayload(ChangeSet.class).ifPresent(changeSets::add);
        run.artifacts().stream().filter(a -> a.payload() instanceof ChangeSet)
                .max(Comparator.comparing(Artifact::createdAt).thenComparingInt(Artifact::version))
                .map(a -> (ChangeSet) a.payload()).filter(cs -> !changeSets.contains(cs)).ifPresent(changeSets::add);
        Optional<ImpactAnalysis> impact = run.acceptedPayload(ImpactAnalysis.class);
        Candidates requests = new Candidates();
        for (ChangeSet changeSet : changeSets) {
            for (ChangeSet.FileChange change : changeSet.changes()) {
                if (change.operation() == ChangeSet.Operation.MODIFY) {
                    requests.add(change.path(), ImpactedSources.Priority.MODIFY, EXPLICIT);
                }
            }
        }
        Set<String> requested = new LinkedHashSet<>();
        changeSets.forEach(cs -> requested.addAll(cs.requestedSources()));
        requested.addAll(requestedSourcesIn(previousError));
        int taken = 0;
        for (String path : requested) {
            if (taken >= MAX_REQUESTED_SOURCES) {
                break;
            }
            String resolved = resolveRequested(path, index);
            if (resolved != null) {
                requests.add(resolved, ImpactedSources.Priority.MODIFY, EXPLICIT);
                taken++;
            }
        }
        String mentions = mentionText(run, feedback, previousError);
        if (!mentions.isBlank()) {
            Set<String> named = new LinkedHashSet<>();
            for (ChangeSet changeSet : changeSets) {
                for (ChangeSet.FileChange change : changeSet.changes()) {
                    if (mentions.contains(change.path()) || mentionsFileName(mentions, change.fileName())) {
                        named.add(change.path());
                    }
                }
            }
            Matcher m = MENTIONED_PATH.matcher(mentions);
            while (m.find()) {
                String path = RepoPaths.normalize(m.group(1));
                if (index != null && !index.isEmpty() && !index.containsPath(path)) {
                    path = IndexLookup.resolvePath(index, path).orElse(path);
                }
                if (WorkspaceSandbox.isAllowedPath(path)) {
                    named.add(path);
                }
            }
            named.forEach(path -> requests.add(path, ImpactedSources.Priority.MODIFY, EXPLICIT));
            if (index != null && !index.isEmpty()) {
                int extra = 0;
                for (String path : IndexLookup.mentionedPaths(index, mentions, MAX_IDENTIFIER_FILES * 4)) {
                    if (extra >= MAX_IDENTIFIER_FILES) {
                        break;
                    }
                    if (requests.add(path, ImpactedSources.Priority.MODIFY, EXPLICIT)) {
                        extra++;
                    }
                }
            }
        }
        impact.ifPresent(analysis -> addComponents(requests, analysis, ImpactAnalysis.ChangeNature.MODIFY,
                ImpactedSources.Priority.MODIFY, index));
        if (staticImpact != null) {
            for (ImpactAnalysis.ImpactedComponent component : staticImpact.components()) {
                if (component.confidence() >= STATIC_SOURCE_CONFIDENCE
                        && (component.nature() == ImpactAnalysis.ChangeNature.MODIFY
                        || component.nature() == ImpactAnalysis.ChangeNature.EXTEND)) {
                    requests.add(resolvePath(component.path(), component.name(), index),
                            ImpactedSources.Priority.MODIFY, component.confidence());
                }
            }
        }
        for (ChangeSet changeSet : changeSets) {
            for (ChangeSet.FileChange change : changeSet.changes()) {
                if (change.operation() == ChangeSet.Operation.DELETE) {
                    requests.add(change.path(), ImpactedSources.Priority.MODIFY, EXPLICIT);
                }
            }
        }
        run.acceptedPayload(DesignSpec.class).ifPresent(design -> {
            for (DesignSpec.Component component : design.components()) {
                if (component.isNew()) {
                    continue;
                }
                String path = component.path().isBlank()
                        ? index == null ? null : IndexLookup.resolveName(index, component.name())
                                .map(IndexedType::path).orElse(null)
                        : resolvePath(component.path(), component.name(), index);
                requests.add(path, ImpactedSources.Priority.EXTEND, DESIGN_COMPONENT);
            }
        });
        impact.ifPresent(analysis -> addComponents(requests, analysis, ImpactAnalysis.ChangeNature.EXTEND,
                ImpactedSources.Priority.EXTEND, index));
        impact.ifPresent(analysis -> addComponents(requests, analysis, ImpactAnalysis.ChangeNature.REGRESSION_RISK,
                ImpactedSources.Priority.REGRESSION_RISK, index));
        return requests.sorted();
    }

    /**
     * Returns the text whose file and type mentions select sources: the feedback, the details of the failed blocking
     * checks of the latest failed verification report (only when there is feedback, i.e. in a feedback loop) and the
     * previous error.
     */
    private static String mentionText(Run run, List<String> feedback, @Nullable String previousError) {
        StringBuilder text = new StringBuilder(String.join("\n", feedback));
        if (!feedback.isEmpty()) {
            run.artifacts().stream().filter(a -> a.payload() instanceof VerificationReport report && !report.passed())
                    .max(Comparator.comparing(Artifact::createdAt).thenComparingInt(Artifact::version))
                    .map(a -> (VerificationReport) a.payload())
                    .ifPresent(report -> report.structuralChecks().stream().filter(Check::failsBlocking)
                            .forEach(check -> text.append('\n').append(check.name()).append(": ")
                                    .append(check.details())));
        }
        if (previousError != null) {
            text.append('\n').append(previousError);
        }
        return text.toString();
    }

    /**
     * Returns the line the engine appends to the last error of a failed attempt that requested sources, or null when
     * there are none: {@link #REQUESTED_SOURCES_PREFIX} followed by at most {@value #MAX_REQUESTED_SOURCES}
     * normalized paths (credential-like tokens redacted, each at most 300 characters).
     *
     * @param requestedSources the requested paths
     * @return the line, or null
     */
    static @Nullable String requestedSourcesLine(List<String> requestedSources) {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : requestedSources) {
            String normalized = SecretPatterns.redact(RepoPaths.normalize(path)).replace(", ", ",")
                    .replace('\n', ' ').replace('\r', ' ');
            if (!normalized.isBlank() && paths.size() < MAX_REQUESTED_SOURCES) {
                paths.add(normalized.length() > 300 ? normalized.substring(0, 300) : normalized);
            }
        }
        return paths.isEmpty() ? null : REQUESTED_SOURCES_PREFIX + String.join(", ", paths);
    }

    /**
     * Returns the paths of the {@link #REQUESTED_SOURCES_PREFIX} lines of an error, in order.
     *
     * @param error the error, or null
     * @return the paths, empty when the error has no such line
     */
    static List<String> requestedSourcesIn(@Nullable String error) {
        if (error == null || !error.contains(REQUESTED_SOURCES_PREFIX)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String line : error.split("\n")) {
            int at = line.indexOf(REQUESTED_SOURCES_PREFIX);
            if (at < 0) {
                continue;
            }
            for (String path : line.substring(at + REQUESTED_SOURCES_PREFIX.length()).split(", ")) {
                if (!path.isBlank()) {
                    paths.add(path.strip());
                }
            }
        }
        return paths;
    }

    /**
     * Resolves a requested source against the index (path, file name or type name); without a match, an allowed
     * repository path is kept as given (the reader reads it from the workspace or notes it as not found) and anything
     * else is dropped.
     */
    private static @Nullable String resolveRequested(String path, @Nullable CodebaseIndex index) {
        String normalized = RepoPaths.normalize(path);
        if (normalized.isEmpty()) {
            return null;
        }
        if (index != null && !index.isEmpty()) {
            Optional<String> resolved = IndexLookup.resolve(index, normalized, normalized);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        return WorkspaceSandbox.isAllowedPath(normalized) ? normalized : null;
    }

    private static boolean mentionsFileName(String text, String fileName) {
        if (fileName.isBlank() || !fileName.contains(".")) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(fileName, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !isNameChar(text.charAt(at - 1));
            int end = at + fileName.length();
            boolean endOk = end >= text.length() || !isNameChar(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static void addComponents(Candidates requests, ImpactAnalysis analysis,
                                      ImpactAnalysis.ChangeNature nature, ImpactedSources.Priority priority,
                                      @Nullable CodebaseIndex index) {
        for (ImpactAnalysis.ImpactedComponent component : analysis.components()) {
            if (component.nature() == nature) {
                requests.add(resolvePath(component.path(), component.name(), index), priority,
                        component.confidence());
            }
        }
    }

    /**
     * Resolves a component path: as given when the index knows it (or there is no index), else through
     * {@link IndexLookup#resolve} (path suffix, file name, type name or a prose name such as "Link entity"), else the
     * indexed type of that exact simple name, else as given (the reader then notes it as not found). Returns null
     * for a component without path or resolvable name.
     */
    static @Nullable String resolvePath(String path, String name, @Nullable CodebaseIndex index) {
        String normalized = RepoPaths.normalize(path);
        if (index == null || index.isEmpty() || (!normalized.isEmpty() && index.containsPath(normalized))) {
            return normalized.isEmpty() ? null : normalized;
        }
        Optional<String> resolved = IndexLookup.resolve(index, normalized, name);
        if (resolved.isPresent()) {
            return resolved.get();
        }
        if (name != null && !name.isBlank()) {
            Optional<IndexedType> type = index.find(name.strip());
            if (type.isPresent()) {
                return type.get().path();
            }
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /** Source requests with a confidence, sorted by priority and then by confidence (stable). */
    private static final class Candidates {

        private record Candidate(String path, ImpactedSources.Priority priority, double confidence) {
        }

        private final List<Candidate> candidates = new ArrayList<>();
        private final Set<String> paths = new HashSet<>();

        /** Adds a request; returns whether the path was not requested before. Null and blank paths are ignored. */
        boolean add(@Nullable String path, ImpactedSources.Priority priority, double confidence) {
            if (path == null) {
                return false;
            }
            String normalized = RepoPaths.normalize(path);
            if (normalized.isEmpty()) {
                return false;
            }
            candidates.add(new Candidate(normalized, priority, confidence));
            return paths.add(normalized);
        }

        /** Returns one request per path, at its highest priority, ordered by priority and then confidence. */
        List<ImpactedSources.Request> sorted() {
            List<Candidate> ordered = new ArrayList<>(candidates);
            ordered.sort(Comparator.comparingInt((Candidate c) -> c.priority().ordinal())
                    .thenComparing(Candidate::confidence, Comparator.reverseOrder()));
            Set<String> seen = new HashSet<>();
            return ordered.stream().filter(c -> seen.add(c.path()))
                    .map(c -> new ImpactedSources.Request(c.path(), c.priority())).toList();
        }
    }

    private @Nullable CodebaseIndex workspaceIndex() {
        try {
            return config.workspaceIndex().get();
        } catch (RuntimeException e) {
            LOG.warn("workspace index unavailable error={}", e.toString());
            return null;
        }
    }

    /**
     * Executes one attempt on the calling thread: the agent runs on a nested task and is awaited with the
     * stage timeout; on timeout the nested task is cancelled and a TIMEOUT failure is returned.
     *
     * @return the outcome, or null when the attempt was cancelled (the caller must then do nothing)
     */
    @Nullable AttemptOutcome execute(StageAgent agent, StageContext context, Duration timeout) {
        return execute(agent, context, timeout, null, timeout);
    }

    /**
     * Executes an attempt like {@link #execute(StageAgent, StageContext, Duration)}, calling {@code heartbeat} every
     * {@code heartbeatInterval} while the agent runs (the engine renews the run's lease with it). A heartbeat that
     * throws is logged and does not affect the attempt; a heartbeat that returns false (this node lost the run's
     * lease) interrupts the agent and ends the attempt with a {@link AttemptOutcome#leaseLost() LEASE_LOST} outcome.
     *
     * @param agent the agent
     * @param context the context
     * @param timeout attempt timeout
     * @param heartbeat called periodically while waiting (false: the lease is lost), or null
     * @param heartbeatInterval interval between heartbeats (positive)
     * @return the outcome, or null when the attempt was cancelled or the executor rejected it
     */
    @Nullable AttemptOutcome execute(StageAgent agent, StageContext context, Duration timeout,
                                     @Nullable BooleanSupplier heartbeat, Duration heartbeatInterval) {
        long start = System.nanoTime();
        Future<StageResult> inner;
        try {
            inner = executor.submit(() -> agent.execute(context));
        } catch (RejectedExecutionException e) {
            return null;
        }
        long timeoutNanos = saturatedNanos(timeout);
        long intervalNanos = Math.max(1L, saturatedNanos(heartbeatInterval));
        StageResult result;
        try {
            StageResult r = null;
            boolean timedOut = false;
            while (true) {
                long remaining = timeoutNanos - (System.nanoTime() - start);
                if (remaining <= 0) {
                    timedOut = true;
                    break;
                }
                try {
                    r = inner.get(heartbeat == null ? remaining : Math.min(remaining, intervalNanos),
                            TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException slice) {
                    if (heartbeat != null && timeoutNanos - (System.nanoTime() - start) > 0 && !beat(heartbeat)) {
                        inner.cancel(true);
                        return AttemptOutcome.leaseLost(context, (System.nanoTime() - start) / 1_000_000L);
                    }
                }
            }
            if (timedOut) {
                throw new TimeoutException();
            }
            result = r != null ? r : StageResult.failure(StageFailure.fatal("agent returned no result"));
        } catch (TimeoutException e) {
            inner.cancel(true);
            result = StageResult.failure(StageFailure.timeout("attempt exceeded the stage timeout of " + timeout));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            result = StageResult.failure(classify(cause));
        } catch (CancellationException e) {
            return null;
        } catch (InterruptedException e) {
            inner.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return new AttemptOutcome(result, latencyMs, context);
    }

    /** Runs a heartbeat; returns false only when it reports a lost lease (a failing heartbeat proves nothing). */
    private static boolean beat(BooleanSupplier heartbeat) {
        try {
            return heartbeat.getAsBoolean();
        } catch (RuntimeException e) {
            LOG.warn("attempt heartbeat failed error={}", e.toString());
            return true;
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /**
     * Maps an exception thrown by an agent to a classified failure.
     *
     * @param t the exception
     * @return the failure
     */
    static StageFailure classify(Throwable t) {
        return switch (t) {
            case StageFailure f -> f;
            case InvalidArtifactException e -> new StageFailure(FailureKind.INVALID_OUTPUT, e.getMessage(), e, null);
            case JacksonException e -> new StageFailure(FailureKind.INVALID_OUTPUT,
                    "output could not be parsed: " + e.getOriginalMessage(), e, null);
            case TimeoutException e -> new StageFailure(FailureKind.TIMEOUT, "timed out: " + e.getMessage(), e, null);
            case IOException e -> new StageFailure(FailureKind.TRANSIENT, "I/O error: " + e.getMessage(), e, null);
            case UncheckedIOException e -> new StageFailure(FailureKind.TRANSIENT, "I/O error: " + e.getMessage(), e,
                    null);
            case InterruptedException e -> new StageFailure(FailureKind.TRANSIENT, "interrupted", e, null);
            default -> new StageFailure(FailureKind.FATAL, t.getClass().getSimpleName() + ": " + t.getMessage(), t,
                    null);
        };
    }
}
