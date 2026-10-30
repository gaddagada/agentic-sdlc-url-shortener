package com.agenticsdlc.orchestrator.core.workspace;

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
     * Returns the component type name used in impact analyses ({@code COMPONENT} maps to {@code OTHER}).
     *
     * @return the component type
     */
    public String componentType() {
        return this == COMPONENT ? "OTHER" : name();
    }
}
