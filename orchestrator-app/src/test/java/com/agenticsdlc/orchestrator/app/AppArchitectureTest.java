package com.agenticsdlc.orchestrator.app;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.conditions.ArchPredicates.are;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.core.engine.InvalidCommandException;
import com.agenticsdlc.orchestrator.core.engine.InvalidRunStateException;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.ProviderCircuit;
import com.agenticsdlc.orchestrator.core.engine.RunLeasedElsewhereException;
import com.agenticsdlc.orchestrator.core.engine.RunNotFoundException;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import jakarta.persistence.Entity;

/**
 * Architecture rules of the control plane, enforced as code.
 */
@AnalyzeClasses(packages = AppArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class AppArchitectureTest {

    static final String ROOT = "com.agenticsdlc.orchestrator.app";
    private static final String API = ROOT + ".api..";
    private static final String PERSISTENCE = ROOT + ".persistence..";
    private static final String CORE_ENGINE = "com.agenticsdlc.orchestrator.core.engine..";

    /** The web layer talks to the engine and its ports, never to JPA adapters. */
    @ArchTest
    static final ArchRule apiDoesNotDependOnPersistence = noClasses().that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(PERSISTENCE);

    /** Persistence adapters know nothing about HTTP. */
    @ArchTest
    static final ArchRule persistenceDoesNotDependOnApi = noClasses().that().resideInAPackage(PERSISTENCE)
            .should().dependOnClassesThat().resideInAPackage(API)
            .orShould().dependOnClassesThat().resideInAPackage("org.springframework.web..");

    /** Only the configuration (and startup) wire the engine; everything else receives it by injection. */
    @ArchTest
    static final ArchRule onlyConfigConstructsTheEngine = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".config..", ROOT + ".startup..")
            .should().callConstructorWhere(DescribedPredicate.describe("a constructor of OrchestrationEngine",
                    call -> call.getTargetOwner().isAssignableTo(OrchestrationEngine.class)))
            .as("only config/startup construct the OrchestrationEngine");

    /**
     * The engine package is used only through its facade, its exceptions and the read models the facade returns
     * ({@link ProviderCircuit} from {@code providerCircuits()}), outside the wiring. {@link RunLeasedElsewhereException}
     * is one of the facade's exceptions: the API maps it to a dedicated 409 that names the node holding the run.
     */
    @ArchTest
    static final ArchRule coreEngineIsUsedOnlyViaTheFacade = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".config..")
            .should().dependOnClassesThat(are(resideInAPackage(CORE_ENGINE)).and(DescribedPredicate.not(
                    JavaClass.Predicates.belongToAnyOf(OrchestrationEngine.class, RunNotFoundException.class,
                            InvalidCommandException.class, InvalidRunStateException.class,
                            RunLeasedElsewhereException.class, ProviderCircuit.class))))

            .as("core engine internals are only used via OrchestrationEngine");

    /**
     * Spring Security is confined to the security package: the API layer reads verified identities through
     * {@code OperatorIdentitySource}, the configuration through {@code ApiAuthorizationRules}.
     */
    @ArchTest
    static final ArchRule springSecurityIsConfinedToTheSecurityPackage = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".security..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
            .as("only the security package depends on Spring Security");

    /** Controllers live in the api package. */
    @ArchTest
    static final ArchRule controllersLiveInApi = classes().that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage(API);

    /** JPA entities live in the persistence package. */
    @ArchTest
    static final ArchRule entitiesLiveInPersistence = classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage(PERSISTENCE);

    /** The LLM adapters do not depend on the web or persistence layers. */
    @ArchTest
    static final ArchRule llmIsIndependentOfWebAndPersistence = noClasses().that().resideInAPackage(ROOT + ".llm..")
            .should().dependOnClassesThat(resideInAPackage(API).or(resideInAPackage(PERSISTENCE)));

    /** The scenario catalog is plain data. */
    @ArchTest
    static final ArchRule scenarioDependsOnNoOtherAppPackage = noClasses().that()
            .resideInAPackage(ROOT + ".scenario..").should().dependOnClassesThat(
                    resideInAPackage(ROOT + "..").and(resideOutsideOfPackage(ROOT + ".scenario..")));

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noGenericExceptions = GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
