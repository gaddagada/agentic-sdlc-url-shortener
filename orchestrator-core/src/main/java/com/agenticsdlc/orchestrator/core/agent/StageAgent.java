package com.agenticsdlc.orchestrator.core.agent;

/**
 * An agent that executes one stage role. Agents are stateless with respect to runs: everything they need
 * is in the {@link StageContext}. Agents never approve anything and never mutate run state; the engine
 * validates, gates and accepts their output.
 *
 * <p>Agents may return {@link StageResult#failure(StageFailure)} or throw; the engine maps exceptions to
 * failures ({@link StageFailure} as-is, timeouts to TIMEOUT, invalid artifacts to INVALID_OUTPUT, I/O errors
 * to TRANSIENT and anything else to FATAL). Implementations should honour thread interruption, which is how
 * the engine cancels in-flight attempts.</p>
 */
public interface StageAgent {

    /**
     * Returns the role this agent plays.
     *
     * @return the role
     */
    AgentRole role();

    /**
     * Returns the agent id: {@code deterministic}, {@code anthropic:<model>} or {@code ollama:<model>}.
     *
     * @return the agent id
     */
    String id();

    /**
     * Executes the stage.
     *
     * @param context immutable stage context
     * @return the result
     * @throws Exception on failure (mapped to a {@link StageFailure} by the engine)
     */
    StageResult execute(StageContext context) throws Exception;
}
