package com.agenticsdlc.orchestrator.core.agent;

import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;

/**
 * The role an agent plays in the SDLC workflow. Each role produces exactly one artifact kind.
 */
public enum AgentRole {
    /** Requirement understanding: intent, ambiguity, normalization. */
    REQUIREMENTS(ArtifactKind.REQUIREMENT_SPEC,
            "requirements analyst who normalizes a requirement, detects ambiguity and writes testable "
                    + "functional and non-functional requirements with acceptance criteria"),
    /** Task decomposition and sequencing. */
    PLANNING(ArtifactKind.TASK_PLAN,
            "delivery planner who decomposes requirements into a dependency-ordered task graph with a critical "
                    + "path and parallel groups"),
    /** Codebase reasoning (impact analysis). */
    CODEBASE_ANALYSIS(ArtifactKind.IMPACT_ANALYSIS,
            "codebase analyst who identifies impacted components, endpoints, entities and regression risks "
                    + "using only the provided codebase index"),
    /** Technical design. */
    ARCHITECTURE(ArtifactKind.DESIGN_SPEC,
            "software architect who designs components, API and data model changes, NFR strategies, security "
                    + "and data handling, with alternatives and decisions"),
    /** Code generation. */
    IMPLEMENTATION(ArtifactKind.CHANGE_SET,
            "senior engineer who produces a change set of files implementing the approved design, with tests "
                    + "and migrations"),
    /** Test design. */
    TEST_DESIGN(ArtifactKind.TEST_PLAN,
            "test engineer who designs test levels and cases with full requirement coverage"),
    /** Verification. */
    VERIFICATION(ArtifactKind.VERIFICATION_REPORT,
            "verifier who checks the change set against the design and test plan and reports actionable "
                    + "feedback"),
    /** Documentation. */
    DOCUMENTATION(ArtifactKind.DOCUMENTATION_BUNDLE,
            "technical writer who produces README, API reference, runbook, changelog and ADRs"),
    /** Security review. */
    SECURITY_REVIEW(ArtifactKind.SECURITY_REVIEW,
            "security reviewer who threat-models the change (STRIDE) and reports findings"),
    /** Release readiness. */
    RELEASE(ArtifactKind.RELEASE_READINESS,
            "release manager who assesses release readiness and prepares the change record");

    private final ArtifactKind artifactKind;
    private final String description;

    AgentRole(ArtifactKind artifactKind, String description) {
        this.artifactKind = artifactKind;
        this.description = description;
    }

    /**
     * Returns the artifact kind produced by this role.
     *
     * @return the artifact kind
     */
    public ArtifactKind artifactKind() {
        return artifactKind;
    }

    /**
     * Returns a short description of the role, used in prompts.
     *
     * @return the description
     */
    public String description() {
        return description;
    }
}
