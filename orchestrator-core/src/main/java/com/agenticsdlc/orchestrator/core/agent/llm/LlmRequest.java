package com.agenticsdlc.orchestrator.core.agent.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * A structured completion request.
 *
 * @param system system prompt
 * @param user user prompt
 * @param maxOutputTokens maximum output tokens
 * @param timeout request timeout
 */
public record LlmRequest(String system, String user, int maxOutputTokens, Duration timeout) {

    /**
     * Validates the request.
     */
    public LlmRequest {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(timeout, "timeout");
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
