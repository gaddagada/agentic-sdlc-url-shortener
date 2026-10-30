package com.agenticsdlc.orchestrator.core;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.StageExecution;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * Architecture rules of the core module (checked with plain JUnit so they do not depend on a specific
 * ArchUnit test engine).
 */
class CoreArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.agenticsdlc.orchestrator.core");
    }

    @Test
    void coreDoesNotDependOnSpring() {
        noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("orchestrator-core is framework independent").check(classes);
    }

    @Test
    void engineDoesNotDependOnDeterministicAgents() {
        noClasses().that().resideInAPackage("..core.engine..").should().dependOnClassesThat()
                .resideInAPackage("..core.agent.deterministic..")
                .because("agents are plugged in through the AgentRegistry").check(classes);
    }

    @Test
    void domainPackagesDoNotDependOnServicePackages() {
        noClasses().that().resideInAnyPackage("..core.model..", "..core.artifact..", "..core.graph..")
                .should().dependOnClassesThat().resideInAnyPackage("..core.engine..", "..core.policy..",
                        "..core.agent.deterministic..", "..core.agent.llm..", "..core.workspace..", "..core.port..",
                        "..core.audit..", "..core.metrics..", "..core.evaluation..")
                .because("model, artifact and graph are the domain layer").check(classes);
    }

    @Test
    void evaluationUsesAgentsOnlyThroughTheEngine() {
        noClasses().that().resideInAPackage("..core.evaluation..").should().dependOnClassesThat()
                .resideInAnyPackage("..core.agent.deterministic..", "..core.agent.llm..")
                .because("the evaluation harness scores whatever agents the engine factory configures")
                .check(classes);
    }

    @Test
    void onlyTheEvaluationHarnessDependsOnEvaluation() {
        noClasses().that().resideOutsideOfPackage("..core.evaluation..").should().dependOnClassesThat()
                .resideInAPackage("..core.evaluation..")
                .because("evaluation is an offline harness on top of the engine, never part of a run").check(classes);
    }

    @Test
    void policyPacksStayInsideThePolicyLayer() {
        noClasses().that().resideInAPackage("..core.policy.pack..").should().dependOnClassesThat()
                .resideInAnyPackage("..core.engine..", "..core.agent..", "..core.workspace..", "..core.port..",
                        "..core.audit..", "..core.metrics..", "..core.evaluation..")
                .because("declarative rules evaluate the policy context only").check(classes);
    }

    @Test
    void domainPackagesUseOnlyTheAgentRoleFromTheAgentPackage() {
        noClasses().that().resideInAnyPackage("..core.model..", "..core.artifact..", "..core.graph..")
                .should().dependOnClassesThat(resideInAPackage("..core.agent").and(not(type(AgentRole.class))))
                .check(classes);
    }

    @Test
    void agentsDoNotDependOnTheEngine() {
        noClasses().that().resideInAnyPackage("..core.agent..", "..core.policy..", "..core.workspace..",
                        "..core.metrics..", "..core.port..", "..core.audit..")
                .should().dependOnClassesThat().resideInAPackage("..core.engine..").check(classes);
        noClasses().that().resideInAPackage("..core.agent.llm..").should().dependOnClassesThat()
                .resideInAPackage("..core.agent.deterministic..").check(classes);
    }

    @Test
    void policyRulesNeverMutateTheRun() {
        // Policy contexts carry a copy of the run; this keeps rules read-only by construction as well.
        Set<String> nonVoidMutators = Set.of("incrementPlanVersion", "removeArtifact", "nextArtifactVersion",
                "recordFaultInjection", "nextAuditSeq");
        DescribedPredicate<JavaMethodCall> runMutator = DescribedPredicate.describe(
                "a mutator of Run or StageExecution", call ->
                        (call.getTargetOwner().isEquivalentTo(Run.class)
                                || call.getTargetOwner().isEquivalentTo(StageExecution.class))
                                && (call.getTarget().getRawReturnType().isEquivalentTo(void.class)
                                || nonVoidMutators.contains(call.getName())));
        noClasses().that().resideInAPackage("..core.policy..").should().callMethodWhere(runMutator)
                .because("policy rules evaluate a read-only view of the run").check(classes);
    }

    @Test
    void noStandardStreamsOrJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
    }

    @Test
    void utilIsALeafPackage() {
        noClasses().that().resideInAPackage("..core.util..").should().dependOnClassesThat(
                resideInAnyPackage("com.agenticsdlc..").and(not(resideInAPackage("..core.util..")))).check(classes);
    }
}
