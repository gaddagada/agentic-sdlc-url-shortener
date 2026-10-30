package com.agenticsdlc.orchestrator.core.artifact;

/**
 * The typed content of an artifact. Implementations are immutable records containing only strings,
 * numbers, booleans, enums, lists, nested records and {@code Map<String, String>}, so that JSON schemas can
 * be derived for LLM structured outputs.
 *
 * <p>Implementations must not declare {@code get*}/{@code is*} methods (they would leak into the JSON
 * form); the three methods below are deliberately not bean accessors.</p>
 */
public sealed interface ArtifactPayload
        permits RequirementSpec, TaskPlan, ImpactAnalysis, DesignSpec, ChangeSet, TestPlan, VerificationReport,
        DocumentationBundle, SecurityReview, ReleaseReadiness {

    /**
     * Returns the artifact kind of this payload.
     *
     * @return the kind
     */
    ArtifactKind kind();

    /**
     * Validates the payload invariants.
     *
     * @throws InvalidArtifactException listing every problem found
     */
    void validate();

    /**
     * Renders the payload as GitHub-flavoured Markdown for the console and documentation.
     *
     * @return Markdown text
     */
    String markdown();
}
