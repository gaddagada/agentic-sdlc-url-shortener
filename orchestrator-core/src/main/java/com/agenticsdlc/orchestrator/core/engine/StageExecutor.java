package com.agenticsdlc.orchestrator.core.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
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
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;

import tools.jackson.core.JacksonException;

/**
 * Builds stage contexts (under the run lock) and executes agent attempts (off the lock) with a timeout,
 * mapping exceptions to classified {@link StageFailure}s.
 */
final class StageExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StageExecutor.class);

    /**
     * Outcome of an attempt.
     *
     * @param result the result
     * @param latencyMs agent latency
     * @param context the context the agent was given
     */
    record AttemptOutcome(StageResult result, long latencyMs, StageContext context) {
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
     * index (REPO runs), options, approvals granted in the current plan and agent settings.
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
        return new StageContext(run.id(), def.id(), def.role(), run.activeRequirement(), run.requirement(), answers,
                questions, upstream, exec.feedback(), exec.attempts(), run.planVersion(), previousError, index,
                run.options(), approvals, settings);
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
        long start = System.nanoTime();
        Future<StageResult> inner;
        try {
            inner = executor.submit(() -> agent.execute(context));
        } catch (RejectedExecutionException e) {
            return null;
        }
        StageResult result;
        try {
            StageResult r = inner.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
