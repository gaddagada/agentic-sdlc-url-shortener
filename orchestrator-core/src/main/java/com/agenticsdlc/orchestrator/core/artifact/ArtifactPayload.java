package com.agenticsdlc.orchestrator.core.artifact;

/**
 * The typed content of an artifact. Implementations are immutable records containing only strings,
 * numbers, booleans, enums, lists, nested records and {@code Map<String, String>}, so that JSON schemas can
 * be derived for LLM structured outputs.
 *
 * <p>Implementations must not declare {@code get*}/{@code is*} methods (they would leak into the JSON
 * form); the methods below are deliberately not bean accessors.</p>
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

    /**
     * Returns a compact summary used as LLM prompt context by stages that consume this artifact only
     * indirectly (not as a direct predecessor). The default is the Markdown rendering truncated to about
     * {@code maxChars} characters with a {@code [truncated]} marker; payloads with bulky content override it.
     *
     * @param maxChars approximate maximum length
     * @return the summary
     */
    default String contextSummary(int maxChars) {
        return Markdown.truncate(markdown(), maxChars);
    }
}
