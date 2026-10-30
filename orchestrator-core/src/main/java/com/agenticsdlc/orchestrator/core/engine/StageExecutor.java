package com.agenticsdlc.orchestrator.core.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
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
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources;
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

    private final WorkflowGraph graph;
    private final EngineConfig config;
    private final AgentSettings settings;
    private final ExecutorService executor;

    StageExecutor(WorkflowGraph graph, EngineConfig config, ExecutorService executor) {
        this.graph = graph;
        this.config = config;
        this.settings = config.agentSettings();
        this.executor = executor;
    }

    /**
     * Builds the immutable context of an attempt: active requirement, clarification answers, accepted
     * artifacts of all transitive predecessors, feedback, attempt, plan version, previous error, workspace
     * index (REPO runs), options, approvals granted in the current plan, agent settings, the stage's direct
     * predecessors (LLM prompts embed only their artifacts and the requirement specification in full) and, for the
     * implementation, verification and security review roles of REPO runs with a workspace root, the bounded
     * source of the impacted files ({@link #impactedSourceRequests}).
     */
    StageContext buildContext(Run run, StageDefinition def, StageExecution exec) {
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
        StageContext context = new StageContext(run.id(), def.id(), def.role(), run.activeRequirement(),
                run.requirement(), answers, questions, upstream, exec.feedback(), exec.attempts(), run.planVersion(),
                previousError, index, run.options(), approvals, settings, graph.predecessors(def.id()));
        if (run.options().workspace() != WorkspaceMode.REPO || config.workspaceRoot() == null
                || !SOURCE_ROLES.contains(def.role())) {
            return context;
        }
        List<ImpactedSources.Request> requests = impactedSourceRequests(run, exec.feedback(), previousError, index);
        if (requests.isEmpty()) {
            return context;
        }
        try {
            ImpactedSources.Result sources = ImpactedSources.read(config.workspaceRoot(), requests,
                    config.sourceContextPerFileChars(), config.sourceContextTotalChars());
            LOG.debug("impacted sources runId={} stageId={} files={} chars={} notes={}", run.id(), def.id(),
                    sources.sources().size(), sources.totalChars(), sources.notes().size());
            return context.withImpactedSources(sources.sources(), sources.notes());
        } catch (RuntimeException e) {
            LOG.warn("impacted sources unavailable runId={} stageId={} error={}", run.id(), def.id(), e.toString());
            return context.withImpactedSources(Map.of(), List.of("impacted sources unavailable: "
                    + e.getClass().getSimpleName()));
        }
    }

    /**
     * Returns the files whose source an implementation, verification or security review attempt sees, by priority:
     * MODIFY targets first (MODIFY changes of the accepted and of the latest change set, files of those change sets
     * or other repository paths named by the stage feedback or the previous error, MODIFY components of the
     * accepted impact analysis, DELETE changes), then EXTEND components, then REGRESSION_RISK components (read only
     * when the budget still allows). Component paths the codebase index does not know are resolved by path suffix
     * or by type name when possible.
     *
     * @param run the run
     * @param feedback the stage's feedback
     * @param previousError the previous attempt's error, or null
     * @param index the codebase index, or null
     * @return the requests, duplicates included (the reader keeps the highest priority)
     */
    static List<ImpactedSources.Request> impactedSourceRequests(Run run, List<String> feedback,
                                                               @Nullable String previousError,
                                                               @Nullable CodebaseIndex index) {
        List<ChangeSet> changeSets = new ArrayList<>();
        run.acceptedPayload(ChangeSet.class).ifPresent(changeSets::add);
        run.artifacts().stream().filter(a -> a.payload() instanceof ChangeSet)
                .max(Comparator.comparing(Artifact::createdAt).thenComparingInt(Artifact::version))
                .map(a -> (ChangeSet) a.payload()).filter(cs -> !changeSets.contains(cs)).ifPresent(changeSets::add);
        Optional<ImpactAnalysis> impact = run.acceptedPayload(ImpactAnalysis.class);
        List<ImpactedSources.Request> requests = new ArrayList<>();
        for (ChangeSet changeSet : changeSets) {
            for (ChangeSet.FileChange change : changeSet.changes()) {
                if (change.operation() == ChangeSet.Operation.MODIFY) {
                    requests.add(new ImpactedSources.Request(change.path(), ImpactedSources.Priority.MODIFY));
                }
            }
        }
        String mentions = String.join("\n", feedback) + (previousError == null ? "" : "\n" + previousError);
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
                if (WorkspaceSandbox.isAllowedPath(path)) {
                    named.add(path);
                }
            }
            named.forEach(path -> requests.add(new ImpactedSources.Request(path, ImpactedSources.Priority.MODIFY)));
        }
        impact.ifPresent(analysis -> addComponents(requests, analysis, ImpactAnalysis.ChangeNature.MODIFY,
                ImpactedSources.Priority.MODIFY, index));
        for (ChangeSet changeSet : changeSets) {
            for (ChangeSet.FileChange change : changeSet.changes()) {
                if (change.operation() == ChangeSet.Operation.DELETE) {
                    requests.add(new ImpactedSources.Request(change.path(), ImpactedSources.Priority.MODIFY));
                }
            }
        }
        impact.ifPresent(analysis -> addComponents(requests, analysis, ImpactAnalysis.ChangeNature.EXTEND,
                ImpactedSources.Priority.EXTEND, index));
        impact.ifPresent(analysis -> addComponents(requests, analysis, ImpactAnalysis.ChangeNature.REGRESSION_RISK,
                ImpactedSources.Priority.REGRESSION_RISK, index));
        return requests;
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

    private static void addComponents(List<ImpactedSources.Request> requests, ImpactAnalysis analysis,
                                      ImpactAnalysis.ChangeNature nature, ImpactedSources.Priority priority,
                                      @Nullable CodebaseIndex index) {
        for (ImpactAnalysis.ImpactedComponent component : analysis.components()) {
            if (component.nature() != nature) {
                continue;
            }
            String path = resolvePath(component.path(), component.name(), index);
            if (path != null) {
                requests.add(new ImpactedSources.Request(path, priority));
            }
        }
    }

    /**
     * Resolves a component path: as given when the index knows it (or there is no index), else the indexed path
     * that ends with it, else the indexed type of that simple name, else as given (the reader then notes it as not
     * found). Returns null for a component without path or resolvable name.
     */
    static @Nullable String resolvePath(String path, String name, @Nullable CodebaseIndex index) {
        String normalized = RepoPaths.normalize(path);
        if (index == null || index.isEmpty() || (!normalized.isEmpty() && index.containsPath(normalized))) {
            return normalized.isEmpty() ? null : normalized;
        }
        if (!normalized.isEmpty()) {
            List<String> suffixed = index.types().stream().map(IndexedType::path)
                    .filter(p -> p.endsWith("/" + normalized)).distinct().toList();
            if (suffixed.size() == 1) {
                return suffixed.getFirst();
            }
        }
        if (name != null && !name.isBlank()) {
            Optional<IndexedType> type = index.find(name.strip());
            if (type.isPresent()) {
                return type.get().path();
            }
        }
        return normalized.isEmpty() ? null : normalized;
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
