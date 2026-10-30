package com.agenticsdlc.orchestrator.core.workspace;

import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;

/**
 * Kind of an indexed source entry.
 */
public enum TypeKind {
    /** {@code @RestController} or {@code @Controller}. */
    CONTROLLER,
    /** {@code @Service}. */
    SERVICE,
    /** {@code @Repository} or a Spring Data repository interface. */
    REPOSITORY,
    /** {@code @Entity}. */
    ENTITY,
    /** {@code @Configuration} or {@code @ConfigurationProperties}. */
    CONFIG,
    /** A servlet filter. */
    FILTER,
    /** A test class (under {@code src/test}). */
    TEST,
    /** Any other type. */
    COMPONENT,
    /** A resource file (migration, configuration file). */
    RESOURCE;

    /**
     * Returns the component type used in impact analyses ({@code COMPONENT} maps to {@code OTHER}).
     *
     * @return the component type
     */
    public ComponentType componentType() {
        return switch (this) {
            case CONTROLLER -> ComponentType.CONTROLLER;
            case SERVICE -> ComponentType.SERVICE;
            case REPOSITORY -> ComponentType.REPOSITORY;
            case ENTITY -> ComponentType.ENTITY;
            case CONFIG -> ComponentType.CONFIG;
            case FILTER -> ComponentType.FILTER;
            case TEST -> ComponentType.TEST;
            case RESOURCE -> ComponentType.RESOURCE;
            case COMPONENT -> ComponentType.OTHER;
        };
    }
}
