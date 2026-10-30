package com.agenticsdlc.orchestrator.core.artifact;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * One dependency or build plugin declared in a Maven POM of the workspace, as read by the dependency inventory of the
 * security review. The inventory is authoritative: the engine's agents fill it from the build files, never from model
 * output.
 *
 * @param module repository-relative path of the declaring POM (for example {@code orchestrator-core/pom.xml})
 * @param groupId group id ({@code org.apache.maven.plugins} for a plugin without one)
 * @param artifactId artifact id
 * @param version the declared version with properties resolved where possible; {@value #MANAGED} when no version is
 *                declared and it is managed (dependencies always, as Maven requires; plugins when a POM of the
 *                workspace manages them), {@value #UNKNOWN} for a plugin without a version whose parent POM lies
 *                outside the workspace (it may or may not manage it) and for a declared version whose property
 *                expression ({@code ${...}}) the workspace cannot resolve (for example a property of an external
 *                parent or BOM, or one set on the command line), {@value #NONE} for a plugin that nothing in the
 *                workspace manages and whose parents are all in the workspace
 * @param scope dependency scope ({@code compile} when not declared, {@code import}, {@code test}, ...), or
 *              {@code build} for plugins
 * @param kind dependency or plugin
 * @param section where it is declared: {@code dependencies}, {@code dependencyManagement}, {@code build/plugins} or
 *                {@code build/pluginManagement}, prefixed with {@code profile:<id>/} inside a profile
 */
public record DependencyRecord(String module, String groupId, String artifactId, String version, String scope,
                               Kind kind, String section) {

    /** Version of a declaration without a version that is managed elsewhere. */
    public static final String MANAGED = "managed";

    /** Version of a plugin without a version whose management cannot be determined from the workspace. */
    public static final String UNKNOWN = "unknown";

    /** Version of a plugin without a version that nothing in the workspace manages. */
    public static final String NONE = "none";

    /** Kind of declaration. */
    public enum Kind {
        /** A {@code <dependency>}. */
        DEPENDENCY,
        /** A build {@code <plugin>}. */
        PLUGIN
    }

    /**
     * Normalizes text.
     */
    public DependencyRecord {
        module = Immutables.text(module);
        groupId = Immutables.text(groupId);
        artifactId = Immutables.text(artifactId);
        version = Immutables.text(version);
        scope = Immutables.text(scope);
        section = Immutables.text(section);
    }

    /**
     * Creates a record in the main section of its kind ({@code dependencies} or {@code build/plugins}).
     *
     * @param module declaring POM path
     * @param groupId group id
     * @param artifactId artifact id
     * @param version version (or {@value #MANAGED})
     * @param scope scope
     * @param kind kind
     */
    public DependencyRecord(String module, String groupId, String artifactId, String version, String scope,
                            Kind kind) {
        this(module, groupId, artifactId, version, scope, kind, kind == Kind.PLUGIN ? "build/plugins"
                : "dependencies");
    }

    /**
     * Returns the coordinate {@code groupId:artifactId:version}.
     *
     * @return the coordinate
     */
    public String coordinate() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
