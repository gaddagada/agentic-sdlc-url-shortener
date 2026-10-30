package com.agenticsdlc.orchestrator.core.artifact;

/**
 * The kind of an artifact. Each kind knows the payload record class it carries, which enables generic
 * typed parsing of persisted payloads.
 */
public enum ArtifactKind {
    /** Output of {@code intake}. */
    REQUIREMENT_SPEC(RequirementSpec.class),
    /** Output of {@code decompose}. */
    TASK_PLAN(TaskPlan.class),
    /** Output of {@code impact}. */
    IMPACT_ANALYSIS(ImpactAnalysis.class),
    /** Output of {@code design}. */
    DESIGN_SPEC(DesignSpec.class),
    /** Output of {@code implement}. */
    CHANGE_SET(ChangeSet.class),
    /** Output of {@code test-design}. */
    TEST_PLAN(TestPlan.class),
    /** Output of {@code verify}. */
    VERIFICATION_REPORT(VerificationReport.class),
    /** Output of {@code document}. */
    DOCUMENTATION_BUNDLE(DocumentationBundle.class),
    /** Output of {@code security-scan}. */
    SECURITY_REVIEW(SecurityReview.class),
    /** Output of {@code release-readiness}. */
    RELEASE_READINESS(ReleaseReadiness.class);

    private final Class<? extends ArtifactPayload> payloadType;

    ArtifactKind(Class<? extends ArtifactPayload> payloadType) {
        this.payloadType = payloadType;
    }

    /**
     * Returns the payload record class of this kind.
     *
     * @return the payload type
     */
    public Class<? extends ArtifactPayload> payloadType() {
        return payloadType;
    }

    /**
     * Returns the kind whose payload type is {@code type}.
     *
     * @param type a payload record class
     * @return the kind
     * @throws IllegalArgumentException when the class is not a known payload type
     */
    public static ArtifactKind forPayloadType(Class<?> type) {
        for (ArtifactKind kind : values()) {
            if (kind.payloadType.equals(type)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Not an artifact payload type: " + type.getName());
    }
}
