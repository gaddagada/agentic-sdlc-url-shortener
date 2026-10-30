package com.agenticsdlc.orchestrator.core.agent.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * A structured completion request.
 *
 * <p>The prompt has three parts, ordered from most to least stable so that providers can cache the prefix:
 * {@code system} (the role's system prompt, identical for every attempt of a role), {@code stableContext} (content
 * identical for every stage of a run: the requirement, the clarification answers, the accepted requirement
 * specification and, for codebase-aware roles, the codebase index) and {@code user} (stage-specific content: the
 * direct predecessors' artifacts, feedback, the previous error, source of impacted files and the output schema).
 * A client sends {@code stableContext} before {@code user} (for example as the first block of the user message,
 * marked as a cache breakpoint); a client that cannot separate them sends {@link #userWithContext()}.</p>
 *
 * @param system system prompt
 * @param stableContext run-stable context sent before {@code user} (empty when none)
 * @param user stage-specific user prompt
 * @param maxOutputTokens maximum output tokens
 * @param timeout request timeout
 */
public record LlmRequest(String system, String stableContext, String user, int maxOutputTokens, Duration timeout) {

    /**
     * Validates the request.
     */
    public LlmRequest {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(stableContext, "stableContext");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(timeout, "timeout");
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    /**
     * Creates a request without a stable context.
     *
     * @param system system prompt
     * @param user user prompt
     * @param maxOutputTokens maximum output tokens
     * @param timeout request timeout
     */
    public LlmRequest(String system, String user, int maxOutputTokens, Duration timeout) {
        this(system, "", user, maxOutputTokens, timeout);
    }

    /**
     * Returns whether the request carries a stable context.
     *
     * @return true when {@link #stableContext()} is not blank
     */
    public boolean hasStableContext() {
        return !stableContext.isBlank();
    }

    /**
     * Returns the complete user-side prompt: the stable context followed by the stage-specific prompt (only the
     * latter when there is no stable context). For clients that send a single user message.
     *
     * @return the combined user prompt
     */
    public String userWithContext() {
        return hasStableContext() ? stableContext + "\n" + user : user;
    }
}
