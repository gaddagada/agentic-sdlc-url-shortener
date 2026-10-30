package com.agenticsdlc.orchestrator.app.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.agenticsdlc.orchestrator.app.api.dto.DependencyView;
import com.agenticsdlc.orchestrator.core.agent.DependencyReview;
import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.FlagKind;

/**
 * Derives the per-declaration hygiene flags of a security review's dependency inventory for display. The review stores
 * the declarations and one finding per kind of flag; the flags of a single declaration follow the inventory's rules on
 * the declaration's own fields: {@code system} scope and {@code LATEST}/{@code RELEASE} versions HIGH, version ranges
 * MEDIUM, SNAPSHOT versions MEDIUM when the review's SNAPSHOT finding names the declaration (SNAPSHOTs of the
 * workspace's own modules are not flagged), duplicate declarations in one section of a module and plugins without a
 * managed version LOW. The findings remain the authoritative summary.
 */
final class DependencyFlags {

    private DependencyFlags() {
    }

    /**
     * Returns the inventory of a review with the flags of every declaration.
     *
     * @param review the security review
     * @return one view per declaration, in inventory order
     */
    static List<DependencyView> of(SecurityReview review) {
        Map<String, Integer> declarations = new HashMap<>();
        for (DependencyRecord d : review.dependencies()) {
            declarations.merge(duplicateKey(d), 1, Integer::sum);
        }
        String snapshotListing = review.findings().stream()
                .filter(f -> f.title().startsWith(DependencyReview.FINDING_PREFIX) && f.title().contains("SNAPSHOT"))
                .map(SecurityReview.Finding::recommendation).reduce("", (a, b) -> a + "\n" + b);
        List<DependencyView> views = new ArrayList<>(review.dependencies().size());
        for (DependencyRecord d : review.dependencies()) {
            List<DependencyView.FlagView> flags = new ArrayList<>();
            String version = d.version().strip();
            String upper = version.toUpperCase(Locale.ROOT);
            if ("system".equalsIgnoreCase(d.scope())) {
                flags.add(flag(FlagKind.SYSTEM_SCOPE, Severity.HIGH));
            }
            if (upper.equals("LATEST") || upper.equals("RELEASE")) {
                flags.add(flag(FlagKind.DYNAMIC_VERSION, Severity.HIGH));
            }
            if (version.startsWith("[") || version.startsWith("(")) {
                flags.add(flag(FlagKind.VERSION_RANGE, Severity.MEDIUM));
            }
            if (upper.endsWith("-SNAPSHOT") && snapshotListing.contains(d.coordinate() + " in " + d.module())) {
                flags.add(flag(FlagKind.SNAPSHOT_VERSION, Severity.MEDIUM));
            }
            if (declarations.getOrDefault(duplicateKey(d), 0) > 1) {
                flags.add(flag(FlagKind.DUPLICATE_DECLARATION, Severity.LOW));
            }
            if (d.kind() == DependencyRecord.Kind.PLUGIN && DependencyRecord.NONE.equals(version)) {
                flags.add(flag(FlagKind.UNVERSIONED_PLUGIN, Severity.LOW));
            }
            views.add(new DependencyView(d.module(), d.groupId(), d.artifactId(), d.version(), d.scope(),
                    d.kind() == null ? "DEPENDENCY" : d.kind().name(), d.section(), d.coordinate(),
                    List.copyOf(flags)));
        }
        return List.copyOf(views);
    }

    private static String duplicateKey(DependencyRecord d) {
        return d.module() + "|" + d.section() + "|" + d.kind() + "|" + d.groupId() + ":" + d.artifactId();
    }

    private static DependencyView.FlagView flag(FlagKind kind, Severity severity) {
        return new DependencyView.FlagView(kind.name(), severity.name());
    }
}
