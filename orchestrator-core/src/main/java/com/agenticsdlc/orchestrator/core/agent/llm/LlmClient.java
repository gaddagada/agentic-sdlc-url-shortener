package com.agenticsdlc.orchestrator.core.agent.llm;

/**
 * LLM provider SPI (implemented in the app for Anthropic and Ollama). Implementations must be thread-safe.
 */
public interface LlmClient {

    /**
     * Returns the provider id: {@code anthropic} or {@code ollama}.
     *
     * @return the provider id
     */
    String providerId();

    /**
     * Returns the model name.
     *
     * @return the model
     */
    String model();

    /**
     * Returns whether the provider is usable (cheap check, cached for 30 s by implementations).
     *
     * @return true when available
     */
    boolean isAvailable();

    /**
     * Requests a structured completion.
     *
     * @param request the request
     * @param outputType the record type of the structured output
     * @param <T> the output type
     * @return the response with the parsed output
     * @throws LlmException on provider errors (with a retryable flag)
     */
    <T> LlmResponse<T> complete(LlmRequest request, Class<T> outputType) throws LlmException;
}
