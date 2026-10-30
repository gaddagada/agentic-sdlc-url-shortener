package com.agenticsdlc.orchestrator.core.graph;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;

/**
 * Built-in workflow definitions.
 */
public final class WorkflowDefinitions {

    /** Id of the default SDLC workflow. */
    public static final String DEFAULT_SDLC_ID = "sdlc-default";

    /** Stage id of requirement understanding. */
    public static final String INTAKE = "intake";
    /** Stage id of task decomposition. */
    public static final String DECOMPOSE = "decompose";
    /** Stage id of codebase impact analysis. */
    public static final String IMPACT = "impact";
    /** Stage id of design. */
    public static final String DESIGN = "design";
    /** Stage id of implementation. */
    public static final String IMPLEMENT = "implement";
    /** Stage id of test design. */
    public static final String TEST_DESIGN = "test-design";
    /** Stage id of verification. */
    public static final String VERIFY = "verify";
    /** Stage id of documentation. */
    public static final String DOCUMENT = "document";
    /** Stage id of the security scan. */
    public static final String SECURITY_SCAN = "security-scan";
    /** Stage id of release readiness. */
    public static final String RELEASE_READINESS = "release-readiness";

    private WorkflowDefinitions() {
    }

    /**
     * Returns the default SDLC workflow ({@code sdlc-default}, version 1).
     *
     * <pre>
     * intake -> (decompose || impact) -> design -> (implement || test-design)
     *        -> verify [joins implement, test-design]; document, security-scan [after implement]
     *        -> release-readiness [joins verify, document, security-scan]
     * </pre>
     *
     * <p>Every stage falls back to the deterministic agent when its primary agent is exhausted.</p>
     *
     * @return the definition
     */
    public static WorkflowDefinition defaultSdlc() {
        Duration threeMinutes = Duration.ofMinutes(3);
        return new WorkflowDefinition(DEFAULT_SDLC_ID, 1, List.of(
                stage(INTAKE, "Requirement understanding", AgentRole.REQUIREMENTS, Set.of(), GateSpec.none(), 3,
                        threeMinutes),
                stage(DECOMPOSE, "Task decomposition", AgentRole.PLANNING, Set.of(INTAKE), GateSpec.none(), 3,
                        threeMinutes),
                stage(IMPACT, "Codebase impact analysis", AgentRole.CODEBASE_ANALYSIS, Set.of(INTAKE),
                        GateSpec.none(), 3, threeMinutes),
                stage(DESIGN, "Technical design", AgentRole.ARCHITECTURE, ordered(DECOMPOSE, IMPACT),
                        GateSpec.exit(ApprovalRequirement.RISK_BASED), 3, threeMinutes),
                stage(IMPLEMENT, "Implementation", AgentRole.IMPLEMENTATION, Set.of(DESIGN),
                        GateSpec.exit(ApprovalRequirement.RISK_BASED), 3, Duration.ofMinutes(5)),
                stage(TEST_DESIGN, "Test design", AgentRole.TEST_DESIGN, Set.of(DESIGN), GateSpec.none(), 3,
                        threeMinutes),
                stage(VERIFY, "Verification", AgentRole.VERIFICATION, ordered(IMPLEMENT, TEST_DESIGN),
                        GateSpec.none(), 2, Duration.ofMinutes(10)),
                stage(DOCUMENT, "Documentation", AgentRole.DOCUMENTATION, Set.of(IMPLEMENT), GateSpec.none(), 3,
                        threeMinutes),
                stage(SECURITY_SCAN, "Security review", AgentRole.SECURITY_REVIEW, Set.of(IMPLEMENT),
                        GateSpec.none(), 2, threeMinutes),
                stage(RELEASE_READINESS, "Release readiness", AgentRole.RELEASE,
                        ordered(VERIFY, DOCUMENT, SECURITY_SCAN), GateSpec.exit(ApprovalRequirement.ALWAYS), 2,
                        threeMinutes)));
    }

    private static StageDefinition stage(String id, String name, AgentRole role, Set<String> dependsOn, GateSpec gates,
                                         int maxAttempts, Duration timeout) {
        return new StageDefinition(id, name, role, dependsOn, gates, RetryPolicy.of(maxAttempts), timeout, true);
    }

    private static Set<String> ordered(String... ids) {
        return new java.util.LinkedHashSet<>(List.of(ids));
    }
}
