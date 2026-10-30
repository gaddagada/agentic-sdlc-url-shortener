package com.agenticsdlc.orchestrator.core.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.util.Clocks;

/**
 * Mutable execution state of one stage within a run. Part of the {@link Run} aggregate and mutated only by
 * the engine while holding the run lock; callers outside the engine only ever see copies.
 */
public final class StageExecution {

    private final String stageId;
    private StageStatus status;
    private int attempts;
    private @Nullable String activeAgent;
    private @Nullable Instant startedAt;
    private @Nullable Instant endedAt;
    private @Nullable String lastError;
    private final List<String> feedback;
    private @Nullable Instant nextAttemptAt;
    private @Nullable Approval pendingApproval;
    /** In-memory token correlating asynchronous completions and wake-ups; never persisted. */
    private long token;

    /**
     * Creates a PENDING stage execution.
     *
     * @param stageId stage id
     */
    public StageExecution(String stageId) {
        this(stageId, StageStatus.PENDING, 0, null, null, null, null, List.of(), null, null);
    }

    /**
     * Creates a stage execution with explicit state (used by snapshots).
     *
     * @param stageId stage id
     * @param status status
     * @param attempts attempts in the current activation
     * @param activeAgent active agent id
     * @param startedAt latest attempt start
     * @param endedAt latest attempt end
     * @param lastError latest error
     * @param feedback reviewer comments and verification feedback
     * @param nextAttemptAt scheduled retry time
     * @param pendingApproval pending approval
     */
    public StageExecution(String stageId, StageStatus status, int attempts, @Nullable String activeAgent,
                          @Nullable Instant startedAt, @Nullable Instant endedAt, @Nullable String lastError,
                          List<String> feedback, @Nullable Instant nextAttemptAt, @Nullable Approval pendingApproval) {
        this.stageId = Objects.requireNonNull(stageId, "stageId");
        this.status = Objects.requireNonNull(status, "status");
        this.attempts = attempts;
        this.activeAgent = activeAgent;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.lastError = lastError;
        this.feedback = new ArrayList<>(feedback);
        this.nextAttemptAt = nextAttemptAt;
        this.pendingApproval = pendingApproval;
    }

    /**
     * Returns a deep copy (the in-memory token is not copied).
     *
     * @return the copy
     */
    public StageExecution copy() {
        return new StageExecution(stageId, status, attempts, activeAgent, startedAt, endedAt, lastError, feedback,
                nextAttemptAt, pendingApproval);
    }

    /** @return the stage id */
    public String stageId() {
        return stageId;
    }

    /** @return the status */
    public StageStatus status() {
        return status;
    }

    /** @return attempts in the current activation (reset on a new activation and on fallback) */
    public int attempts() {
        return attempts;
    }

    /** @return the active agent id, null before the first attempt of an activation */
    public @Nullable String activeAgent() {
        return activeAgent;
    }

    /** @return start of the latest attempt */
    public @Nullable Instant startedAt() {
        return startedAt;
    }

    /** @return end of the latest attempt */
    public @Nullable Instant endedAt() {
        return endedAt;
    }

    /** @return the latest error */
    public @Nullable String lastError() {
        return lastError;
    }

    /** @return feedback entries (immutable view) */
    public List<String> feedback() {
        return List.copyOf(feedback);
    }

    /** @return the scheduled retry time */
    public @Nullable Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    /** @return the pending approval, if any */
    public @Nullable Approval pendingApproval() {
        return pendingApproval;
    }

    /**
     * Returns the duration of the latest attempt.
     *
     * @return milliseconds, or null while running or before the first attempt
     */
    public @Nullable Long durationMs() {
        return Clocks.millisBetweenOrNull(startedAt, endedAt);
    }

    // ---- mutators (engine only, under the run lock) ----

    /**
     * Sets the status.
     *
     * @param newStatus status
     */
    public void setStatus(StageStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus);
    }

    /**
     * Sets the attempt counter.
     *
     * @param value attempts
     */
    public void setAttempts(int value) {
        this.attempts = value;
    }

    /**
     * Sets the active agent id.
     *
     * @param value agent id or null
     */
    public void setActiveAgent(@Nullable String value) {
        this.activeAgent = value;
    }

    /**
     * Records the start of an attempt.
     *
     * @param at start time
     */
    public void markStarted(Instant at) {
        this.startedAt = at;
        this.endedAt = null;
        this.nextAttemptAt = null;
    }

    /**
     * Sets the end of the latest attempt.
     *
     * @param at end time or null
     */
    public void setEndedAt(@Nullable Instant at) {
        this.endedAt = at;
    }

    /**
     * Sets the latest error.
     *
     * @param value error or null
     */
    public void setLastError(@Nullable String value) {
        this.lastError = value;
    }

    /**
     * Appends feedback.
     *
     * @param entry feedback entry
     */
    public void addFeedback(String entry) {
        this.feedback.add(entry);
    }

    /**
     * Clears the feedback (a re-plan starts a new plan).
     */
    public void clearFeedback() {
        this.feedback.clear();
    }

    /**
     * Sets the scheduled retry time.
     *
     * @param at time or null
     */
    public void setNextAttemptAt(@Nullable Instant at) {
        this.nextAttemptAt = at;
    }

    /**
     * Sets the pending approval.
     *
     * @param approval approval or null
     */
    public void setPendingApproval(@Nullable Approval approval) {
        this.pendingApproval = approval;
    }

    /**
     * Returns the in-memory correlation token (engine internal).
     *
     * @return token
     */
    public long token() {
        return token;
    }

    /**
     * Sets the in-memory correlation token (engine internal).
     *
     * @param value token
     */
    public void setToken(long value) {
        this.token = value;
    }

    /**
     * Resets the execution for a new activation: attempts 0, no active agent, no retry, no pending approval.
     *
     * @param newStatus PENDING or STALE
     */
    public void reset(StageStatus newStatus) {
        this.status = newStatus;
        this.attempts = 0;
        this.activeAgent = null;
        this.nextAttemptAt = null;
        this.pendingApproval = null;
        this.token = 0L;
    }
}
