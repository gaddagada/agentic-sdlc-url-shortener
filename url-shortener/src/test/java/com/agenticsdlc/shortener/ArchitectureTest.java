package com.agenticsdlc.shortener;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import jakarta.persistence.Entity;

import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture as code: layering and coding rules of the shortener module.
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "com.agenticsdlc.shortener";
    private static final String MODULE = ROOT + "..";
    private static final String API = ROOT + ".api..";
    private static final String SERVICE = ROOT + ".service..";
    private static final String REPOSITORY = ROOT + ".repository..";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String CONFIG = ROOT + ".config..";

    @ArchTest
    static final ArchRule apiDependsOnlyOnServiceAndDomain = classes().that().resideInAPackage(API)
            .should().onlyDependOnClassesThat(
                    resideOutsideOfPackage(MODULE).or(resideInAnyPackage(API, SERVICE, DOMAIN)))
            .because("controllers use services and domain types, never repositories or configuration");

    @ArchTest
    static final ArchRule serviceDoesNotDependOnApi = noClasses().that().resideInAPackage(SERVICE)
            .should().dependOnClassesThat().resideInAPackage(API);

    @ArchTest
    static final ArchRule serviceIsTransportAgnostic = noClasses().that().resideInAPackage(SERVICE)
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web..")
            .because("business logic must be reusable outside HTTP (batch, messaging)");

    @ArchTest
    static final ArchRule domainDependsOnNothingInTheModule = classes().that().resideInAPackage(DOMAIN)
            .should().onlyDependOnClassesThat(resideOutsideOfPackage(MODULE).or(resideInAPackage(DOMAIN)));

    @ArchTest
    static final ArchRule repositoriesDependOnlyOnDomain = classes().that().resideInAPackage(REPOSITORY)
            .should().onlyDependOnClassesThat(
                    resideOutsideOfPackage(MODULE).or(resideInAnyPackage(REPOSITORY, DOMAIN)));

    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy(API)
            .layer("Service").definedBy(SERVICE)
            .layer("Repository").definedBy(REPOSITORY)
            .layer("Domain").definedBy(DOMAIN)
            .layer("Config").definedBy(CONFIG)
            .whereLayer("Api").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api", "Config")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Config").mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule controllersLiveInApi = classes().that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage(API);

    @ArchTest
    static final ArchRule entitiesLiveInDomain = classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage(DOMAIN);

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noGenericExceptions = GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
