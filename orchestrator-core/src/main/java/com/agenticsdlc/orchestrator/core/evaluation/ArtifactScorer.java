package com.agenticsdlc.orchestrator.core.evaluation;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactPayload;

/**
 * A deterministic scorer of one artifact kind: the same artifact and context always give the same score.
 *
 * @param <T> payload type
 */
public interface ArtifactScorer<T extends ArtifactPayload> {

    /**
     * Returns the payload type this scorer scores.
     *
     * @return the payload class
     */
    Class<T> payloadType();

    /**
     * Returns the artifact kind this scorer scores.
     *
     * @return the kind
     */
    ArtifactKind kind();

    /**
     * Scores an artifact.
     *
     * @param payload the artifact payload
     * @param context the run context
     * @return the score
     */
    Score score(T payload, ScoringContext context);

    /**
     * Scores an artifact of unknown static type.
     *
     * @param payload a payload of {@link #payloadType()}
     * @param context the run context
     * @return the score
     * @throws ClassCastException when the payload has another type
     */
    default Score scoreAny(ArtifactPayload payload, ScoringContext context) {
        return score(payloadType().cast(payload), context);
    }
}
