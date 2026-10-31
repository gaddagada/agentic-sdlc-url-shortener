package com.agenticsdlc.orchestrator.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A classified failure of an agent attempt. It can be thrown by an agent or returned through
 * {@link StageResult#failure(StageFailure)}; the engine retries retryable kinds within the stage's retry
 * policy, then falls back, then fails the stage.
 *
 * <p>A failure may name {@linkplain #requestedSources() sources the failed attempt asked to see} (for example the
 * {@code requestedSources} of a change set the workspace sandbox rejected); the engine shows them in the next
 * attempt.</p>
 */
public final class StageFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureKind kind;
    private final transient @Nullable LlmTrace trace;
    private final List<String> requestedSources;

    /**
     * Creates a failure.
     *
     * @param kind the kind
     * @param message the message
     */
    public StageFailure(FailureKind kind, String message) {
        this(kind, message, null, null);
    }

    /**
     * Creates a failure with an optional cause and LLM usage (tokens consumed by the failed attempt still
     * count towards the run's budget).
     *
     * @param kind the kind
     * @param message the message
     * @param cause the cause, or null
     * @param trace LLM usage, or null
     */
    public StageFailure(FailureKind kind, String message, @Nullable Throwable cause, @Nullable LlmTrace trace) {
        this(kind, message, cause, trace, List.of());
    }

    /**
     * Creates a failure that also names the sources the failed attempt asked to see in the next attempt.
     *
     * @param kind the kind
     * @param message the message
     * @param cause the cause, or null
     * @param trace LLM usage, or null
     * @param requestedSources repository paths the next attempt should show (blank entries are dropped)
     */
    public StageFailure(FailureKind kind, String message, @Nullable Throwable cause, @Nullable LlmTrace trace,
                        List<String> requestedSources) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.trace = trace;
        List<String> requested = new ArrayList<>();
        for (String path : requestedSources) {
            if (path != null && !path.isBlank()) {
                requested.add(path.strip());
            }
        }
        this.requestedSources = List.copyOf(requested);
    }

    /**
     * Creates a TRANSIENT failure.
     *
     * @param message message
     * @return the failure
     */
    public static StageFailure transientFailure(String message) {
        return new StageFailure(FailureKind.TRANSIENT, message);
    }

    /**
     * Creates a FATAL failure.
     *
     * @param message message
     * @return the failure
     */
    public static StageFailure fatal(String message) {
        return new StageFailure(FailureKind.FATAL, message);
    }

    /**
     * Creates a TIMEOUT failure.
     *
     * @param message message
     * @return the failure
     */
    public static StageFailure timeout(String message) {
        return new StageFailure(FailureKind.TIMEOUT, message);
    }

    /**
     * Creates an INVALID_OUTPUT failure.
     *
     * @param message message
     * @return the failure
     */
    public static StageFailure invalidOutput(String message) {
        return new StageFailure(FailureKind.INVALID_OUTPUT, message);
    }

    /**
     * Returns the failure kind.
     *
     * @return the kind
     */
    public FailureKind kind() {
        return kind;
    }

    /**
     * Returns whether the failure may be retried.
     *
     * @return true unless FATAL
     */
    public boolean retryable() {
        return kind.retryable();
    }

    /**
     * Returns LLM usage of the failed attempt, if any.
     *
     * @return the trace or null
     */
    public @Nullable LlmTrace trace() {
        return trace;
    }

    /**
     * Returns the repository paths the failed attempt asked to see in the next attempt (for example the
     * {@code requestedSources} of a rejected change set).
     *
     * @return the paths, empty when none
     */
    public List<String> requestedSources() {
        return requestedSources;
    }

    /**
     * Returns {@code KIND: message}.
     *
     * @return the description
     */
    public String describe() {
        return kind + ": " + getMessage();
    }
}
