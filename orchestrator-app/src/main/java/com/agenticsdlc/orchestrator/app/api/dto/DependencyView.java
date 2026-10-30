package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

/**
 * One declared Maven dependency or build plugin of a security review's dependency inventory, with the hygiene flags
 * that apply to it. The inventory is read from the build files by the engine (never from model output); it is a
 * hygiene check, not a vulnerability (CVE) scan.
 *
 * @param module repository-relative path of the declaring POM
 * @param groupId group id
 * @param artifactId artifact id
 * @param version declared version ({@code managed}, {@code unknown} or {@code none} when not declared)
 * @param scope dependency scope, or {@code build} for plugins
 * @param kind {@code DEPENDENCY} or {@code PLUGIN}
 * @param section where it is declared ({@code dependencies}, {@code dependencyManagement}, {@code build/plugins},
 *                {@code build/pluginManagement}, prefixed with {@code profile:<id>/} inside a profile)
 * @param coordinate {@code groupId:artifactId:version}
 * @param flags hygiene flags of this declaration (empty when none)
 */
public record DependencyView(String module, String groupId, String artifactId, String version, String scope,
                             String kind, String section, String coordinate, List<FlagView> flags) {

    /**
     * A hygiene flag.
     *
     * @param kind {@code SYSTEM_SCOPE}, {@code DYNAMIC_VERSION}, {@code VERSION_RANGE}, {@code SNAPSHOT_VERSION},
     *             {@code DUPLICATE_DECLARATION} or {@code UNVERSIONED_PLUGIN}
     * @param severity {@code HIGH}, {@code MEDIUM} or {@code LOW}
     */
    public record FlagView(String kind, String severity) {
    }
}
