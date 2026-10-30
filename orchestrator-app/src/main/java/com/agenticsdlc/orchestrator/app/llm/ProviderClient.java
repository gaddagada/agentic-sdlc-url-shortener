package com.agenticsdlc.orchestrator.app.llm;

import java.time.Duration;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmClient;

/**
 * An {@link LlmClient} as configured in this application: it also reports a human-readable availability detail
 * and the request limits the stage agents should use.
 */
public interface ProviderClient extends LlmClient {

    /**
     * Returns a human-readable availability detail, for example {@code ANTHROPIC_API_KEY not set}. Must never
     * contain secrets.
     *
     * @return the detail
     */
    String detail();

    /**
     * Returns the maximum output tokens per request.
     *
     * @return tokens
     */
    int maxOutputTokens();

    /**
     * Returns the request timeout.
     *
     * @return timeout
     */
    Duration requestTimeout();
}
