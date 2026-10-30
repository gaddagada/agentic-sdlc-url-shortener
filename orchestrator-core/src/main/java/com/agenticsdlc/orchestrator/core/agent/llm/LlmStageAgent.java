package com.agenticsdlc.orchestrator.core.agent.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.ClarificationGate;
import com.agenticsdlc.orchestrator.core.agent.CompensationRequest;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.LlmTrace;
import com.agenticsdlc.orchestrator.core.agent.SandboxMaterializer;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.InvalidArtifactException;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.ClarificationQuestion;
import com.agenticsdlc.orchestrator.core.util.Hashes;

import tools.jackson.core.JacksonException;

/**
 * A stage agent backed by an {@link LlmClient}, generic over the payload record it produces.
 *
 * <p>Builds the system prompt ({@link PromptTemplates#system(AgentRole)}) and the user prompt (requirement,
 * clarification answers, upstream artifacts, codebase index, feedback, previous error, output schema), calls the
 * client for a typed structured output and validates it. Upstream artifacts follow the context policy of
 * {@link PromptTemplates}: full JSON for the stage's direct predecessors and the requirement specification, a
 * compact summary for every other transitive predecessor. Invalid output is an INVALID_OUTPUT failure
 * (retryable; the next attempt's prompt includes the validation error), and so is a change set the workspace
 * sandbox rejects (for example CREATE over an existing file); retryable provider errors are TRANSIENT; other
 * provider errors and an unavailable provider are FATAL, which engages the deterministic fallback. Usage, model
 * and prompt hash are reported for provenance and budgets, also for failed attempts. The requirements role
 * applies the intrinsic clarification gate; approvals an LLM might put into a release change record are
 * discarded (agents never approve anything).</p>
 *
 * @param <T> payload type
 */
public final class LlmStageAgent<T extends ArtifactPayload> implements StageAgent {

    /** Default maximum output tokens. */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 16_000;

    /** Default request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);

    private final AgentRole role;
    private final Class<T> payloadType;
    private final LlmClient client;
    private final JsonCodec codec;
    private final int maxOutputTokens;
    private final Duration timeout;
    private final Map<String, Object> schema;

    /**
     * Creates the agent.
     *
     * @param role the role
     * @param payloadType payload record class (must be the role's artifact payload type)
     * @param client the LLM client
     * @param codec JSON codec
     * @param maxOutputTokens maximum output tokens
     * @param timeout request timeout
     */
    public LlmStageAgent(AgentRole role, Class<T> payloadType, LlmClient client, JsonCodec codec, int maxOutputTokens,
                         Duration timeout) {
        this.role = Objects.requireNonNull(role, "role");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.client = Objects.requireNonNull(client, "client");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.maxOutputTokens = maxOutputTokens;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (!role.artifactKind().payloadType().equals(payloadType)) {
            throw new IllegalArgumentException("role " + role + " produces " + role.artifactKind().payloadType()
                    .getSimpleName() + ", not " + payloadType.getSimpleName());
        }
        this.schema = JsonSchemas.forRecord(payloadType);
    }

    /**
     * Creates the agent of a role with its payload type.
     *
     * @param role the role
     * @param client the client
     * @param codec the codec
     * @param maxOutputTokens maximum output tokens
     * @param timeout request timeout
     * @return the agent
     */
    public static LlmStageAgent<? extends ArtifactPayload> forRole(AgentRole role, LlmClient client, JsonCodec codec,
                                                                   int maxOutputTokens, Duration timeout) {
        return create(role, role.artifactKind().payloadType(), client, codec, maxOutputTokens, timeout);
    }

    private static <P extends ArtifactPayload> LlmStageAgent<P> create(AgentRole role, Class<P> type, LlmClient client,
                                                                     JsonCodec codec, int maxOutputTokens,
                                                                     Duration timeout) {
        return new LlmStageAgent<>(role, type, client, codec, maxOutputTokens, timeout);
    }

    /**
     * Creates one agent per role.
     *
     * @param client the client
     * @param codec the codec
     * @param maxOutputTokens maximum output tokens
     * @param timeout request timeout
     * @return the agents
     */
    public static List<StageAgent> forAllRoles(LlmClient client, JsonCodec codec, int maxOutputTokens,
                                               Duration timeout) {
        return Arrays.stream(AgentRole.values())
                .<StageAgent>map(r -> forRole(r, client, codec, maxOutputTokens, timeout)).toList();
    }

    /**
     * Creates one agent per role with default limits (16 000 output tokens, 180 s).
     *
     * @param client the client
     * @param codec the codec
     * @return the agents
     */
    public static List<StageAgent> forAllRoles(LlmClient client, JsonCodec codec) {
        return forAllRoles(client, codec, DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_TIMEOUT);
    }

    @Override
    public AgentRole role() {
        return role;
    }

    @Override
    public String id() {
        return client.providerId() + ":" + client.model();
    }

    /**
     * Returns the payload type.
     *
     * @return the payload record class
     */
    public Class<T> payloadType() {
        return payloadType;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        if (!client.isAvailable()) {
            throw StageFailure.fatal("provider " + client.providerId() + " is not available");
        }
        String system = PromptTemplates.system(role);
        String user = PromptTemplates.user(context, codec, schema);
        String promptHash = Hashes.sha256Hex(system + "\n\n" + user);
        LlmResponse<T> response;
        try {
            response = client.complete(new LlmRequest(system, user, maxOutputTokens, timeout), payloadType);
        } catch (LlmException e) {
            throw new StageFailure(e.retryable() ? FailureKind.TRANSIENT : FailureKind.FATAL,
                    "provider " + client.providerId() + " error: " + e.getMessage(), e,
                    new LlmTrace(client.providerId(), client.model(), promptHash, 0L, 0L));
        } catch (JacksonException e) {
            throw new StageFailure(FailureKind.INVALID_OUTPUT, "output could not be parsed: " + e.getOriginalMessage(),
                    e, new LlmTrace(client.providerId(), client.model(), promptHash, 0L, 0L));
        }
        LlmUsage usage = response.usage() == null ? LlmUsage.none() : response.usage();
        LlmTrace trace = new LlmTrace(client.providerId(), response.model() == null || response.model().isBlank()
                ? client.model() : response.model(), promptHash, usage.inputTokens(), usage.outputTokens());
        T payload = response.output();
        if (payload == null) {
            throw new StageFailure(FailureKind.INVALID_OUTPUT, "the provider returned no structured output", null,
                    trace);
        }
        try {
            payload.validate();
        } catch (InvalidArtifactException e) {
            throw new StageFailure(FailureKind.INVALID_OUTPUT, e.getMessage(), e, trace);
        }
        ArtifactPayload accepted = payload;
        if (payload instanceof RequirementSpec spec) {
            Optional<List<ClarificationQuestion>> questions = ClarificationGate.questionsIfNeeded(spec, context);
            if (questions.isPresent()) {
                return new StageResult.NeedsClarification(questions.get(), "ambiguity score "
                        + spec.ambiguity().score() + " reaches the clarification threshold", trace);
            }
        }
        if (payload instanceof ReleaseReadiness release && !release.changeRecord().approvals().isEmpty()) {
            ReleaseReadiness.ChangeRecord record = release.changeRecord();
            accepted = release.withChangeRecord(new ReleaseReadiness.ChangeRecord(record.summary(), record.riskLevel(),
                    record.rollbackPlan(), List.of(), record.artifactIds(), record.policyVerdict()));
        }
        List<CompensationRequest> compensations = new ArrayList<>();
        if (payload instanceof ChangeSet changeSet) {
            try {
                SandboxMaterializer.materialize(changeSet, context).ifPresent(compensations::add);
            } catch (StageFailure e) {
                // keep the kind and message, but report the tokens this attempt consumed
                throw new StageFailure(e.kind(), e.getMessage(), e, trace);
            }
        }
        return new StageResult.Success(accepted, "generated by " + id() + " (" + usage.inputTokens() + " input / "
                + usage.outputTokens() + " output tokens)", compensations, trace);
    }
}
