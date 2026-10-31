package com.agenticsdlc.orchestrator.core.agent.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.util.RepoPaths;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndex;
import com.agenticsdlc.orchestrator.core.workspace.IndexLookup;
import com.agenticsdlc.orchestrator.core.workspace.IndexedType;

/**
 * Reconciles model-written impact analyses and designs with the indexed tree of a REPO run, so that the components
 * downstream stages rely on carry real paths and nothing the static analysis is sure about is silently dropped.
 *
 * <p>Impact analysis ({@link #impact}):</p>
 * <ol>
 *   <li>every component that is not NEW and whose path the index does not list is resolved by path suffix, file name
 *       or type name ({@link IndexLookup#resolve}); a resolved type also gets its simple name;</li>
 *   <li>a missing component type is taken from the index (RESOURCE for migrations and configuration files, else
 *       OTHER), a missing change nature from the static analysis of the same path (else MODIFY for an indexed path,
 *       NEW otherwise); a second component for the same path is dropped;</li>
 *   <li>static candidates with confidence of at least {@value #MERGE_CONFIDENCE} and nature MODIFY or EXTEND that the
 *       model omitted are added with their static nature, confidence and reasons;</li>
 *   <li>empty {@code entities}, {@code migrations} and {@code endpoints} are back-filled from the static analysis;</li>
 *   <li>{@code existingCodebase}, {@code workspaceRoot} and {@code indexedFiles} are taken from the index.</li>
 * </ol>
 * <p>Every change is recorded in {@link ImpactAnalysis#reconciliation()} (whatever the model wrote there is
 * replaced).</p>
 *
 * <p>Design ({@link #design}): every component that is not new and has no indexed path is resolved the same way;
 * new components are left as written.</p>
 */
final class IndexReconciliation {

    /** Minimum confidence of a static candidate that is merged into a model-written impact analysis. */
    static final double MERGE_CONFIDENCE = 0.8;

    private IndexReconciliation() {
    }

    /**
     * Returns whether the context carries a non-empty workspace index to reconcile with.
     *
     * @param context the stage context
     * @return true for REPO contexts with an indexed tree
     */
    static boolean applies(StageContext context) {
        CodebaseIndex index = context.workspaceIndex();
        return index != null && !index.isEmpty();
    }

    /**
     * Reconciles a model-written impact analysis with the index and the static analysis of the context.
     *
     * @param analysis the model output
     * @param context the stage context (index and optional static analysis)
     * @return the reconciled analysis (the input when the context has no index)
     */
    static ImpactAnalysis impact(ImpactAnalysis analysis, StageContext context) {
        CodebaseIndex index = context.workspaceIndex();
        if (index == null || index.isEmpty()) {
            return analysis;
        }
        ImpactAnalysis staticImpact = context.staticImpact();
        Map<String, ImpactedComponent> staticByPath = new HashMap<>();
        if (staticImpact != null) {
            staticImpact.components().forEach(c -> staticByPath.putIfAbsent(RepoPaths.normalize(c.path()), c));
        }
        List<String> notes = new ArrayList<>();
        List<ImpactedComponent> components = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ImpactedComponent c : analysis.components()) {
            String name = c.name();
            String path = RepoPaths.normalize(c.path());
            ChangeNature nature = c.nature();
            ComponentType type = c.type();
            boolean indexed = !path.isEmpty() && index.containsPath(path);
            if (!indexed && nature != ChangeNature.NEW) {
                Optional<String> resolved = IndexLookup.resolve(index, path, name);
                if (resolved.isPresent()) {
                    String was = path.isEmpty() ? "no path" : "path " + path;
                    path = resolved.get();
                    indexed = true;
                    Optional<IndexedType> resolvedType = typeAt(index, path);
                    String newName = resolvedType.map(IndexedType::simpleName).orElse(name.isBlank()
                            ? fileName(path) : name);
                    notes.add("resolved component '" + label(name) + "' (" + was + ") to " + path
                            + (newName.equals(name) ? "" : " (" + newName + ")"));
                    name = newName;
                } else {
                    notes.add("component '" + label(name) + "'" + (path.isEmpty() ? " has no path" : ": path " + path
                            + " is not in the codebase index") + " and could not be resolved to an indexed file");
                }
            }
            if (indexed && !seen.add(path)) {
                notes.add("dropped duplicate component '" + label(name) + "' for " + path);
                continue;
            }
            if (type == null) {
                type = typeOf(index, path, indexed);
                notes.add("component '" + label(name) + "' had no type; set to " + type);
            }
            if (nature == null) {
                ImpactedComponent known = staticByPath.get(path);
                nature = known != null ? known.nature() : indexed ? ChangeNature.MODIFY : ChangeNature.NEW;
                notes.add("component '" + label(name) + "' had no change type; set to " + nature
                        + (known != null ? " (static analysis)" : ""));
            }
            if (name.isBlank()) {
                name = path.isEmpty() ? "unnamed component" : fileName(path);
            }
            ImpactedComponent known = staticByPath.get(path);
            if (known != null && known.confidence() >= MERGE_CONFIDENCE && isChange(known.nature())
                    && nature == ChangeNature.REGRESSION_RISK) {
                notes.add("model rates " + name + " " + nature + " where static analysis rates it " + known.nature()
                        + " (" + format(known.confidence()) + "); its source is shown to the implementation stage");
            }
            components.add(new ImpactedComponent(name, type, path, c.confidence(), c.reasons(), nature));
        }
        List<ImpactedComponent> merged = new ArrayList<>();
        List<ImpactAnalysis.ImpactedEndpoint> endpoints = analysis.endpoints();
        List<String> entities = analysis.entities();
        List<String> migrations = analysis.migrations();
        if (staticImpact != null && staticImpact.existingCodebase()) {
            for (ImpactedComponent s : staticImpact.components()) {
                String path = RepoPaths.normalize(s.path());
                if (s.confidence() >= MERGE_CONFIDENCE && isChange(s.nature()) && !path.isEmpty()
                        && !seen.contains(path)) {
                    seen.add(path);
                    List<String> reasons = new ArrayList<>(s.reasons());
                    reasons.add("added from static analysis: the model omitted this component");
                    merged.add(new ImpactedComponent(s.name(), s.type(), path, s.confidence(), reasons, s.nature()));
                    notes.add("merged from static analysis: " + s.name() + " (" + s.type() + ", " + s.nature()
                            + ", confidence " + format(s.confidence()) + ", " + path + ")");
                }
            }
            if (entities.isEmpty() && !staticImpact.entities().isEmpty()) {
                entities = staticImpact.entities();
                notes.add("entities back-filled from static analysis: " + String.join(", ", entities));
            }
            if (migrations.isEmpty() && !staticImpact.migrations().isEmpty()) {
                migrations = staticImpact.migrations();
                notes.add("migrations back-filled from static analysis: " + String.join(", ", migrations));
            }
            if (endpoints.isEmpty() && !staticImpact.endpoints().isEmpty()) {
                endpoints = staticImpact.endpoints();
                notes.add("endpoints back-filled from static analysis: " + String.join(", ", endpoints.stream()
                        .map(e -> e.method() + " " + e.path()).toList()));
            }
        }
        components.addAll(merged);
        if (!analysis.existingCodebase()) {
            notes.add("existingCodebase set to true: the workspace index lists " + index.indexedFiles() + " files");
        }
        String root = analysis.workspaceRoot().isBlank() || "none".equals(analysis.workspaceRoot())
                ? index.rootName() : analysis.workspaceRoot();
        int indexedFiles = analysis.indexedFiles() > 0 ? analysis.indexedFiles() : index.indexedFiles();
        return new ImpactAnalysis(root, indexedFiles, true, components, endpoints, entities, migrations,
                analysis.dataFlows(), analysis.riskFactors(), analysis.suggestedTests(), analysis.summary(), notes);
    }

    /**
     * Resolves the paths of the existing components of a model-written design against the index: a component that
     * is not new and whose path the index does not list gets the path its path or name resolves to
     * ({@link IndexLookup#resolve}); new components and unresolvable ones are left as written.
     *
     * @param design the model output
     * @param context the stage context
     * @return the design with resolved component paths (the input when nothing changed or there is no index)
     */
    static DesignSpec design(DesignSpec design, StageContext context) {
        CodebaseIndex index = context.workspaceIndex();
        if (index == null || index.isEmpty()) {
            return design;
        }
        boolean changed = false;
        List<DesignSpec.Component> components = new ArrayList<>();
        for (DesignSpec.Component c : design.components()) {
            String path = RepoPaths.normalize(c.path());
            if (c.isNew() || (!path.isEmpty() && index.containsPath(path))) {
                components.add(c);
                continue;
            }
            Optional<String> resolved = IndexLookup.resolve(index, path, c.name());
            if (resolved.isPresent() && !resolved.get().equals(c.path())) {
                components.add(c.withPath(resolved.get()));
                changed = true;
            } else {
                components.add(c);
            }
        }
        if (!changed) {
            return design;
        }
        return new DesignSpec(design.overview(), components, design.apiChanges(), design.dataModelChanges(),
                design.sequenceFlows(), design.nfrStrategies(), design.securityConsiderations(), design.dataHandling(),
                design.alternativesConsidered(), design.risk(), design.decisions());
    }

    private static boolean isChange(@Nullable ChangeNature nature) {
        return nature == ChangeNature.MODIFY || nature == ChangeNature.EXTEND;
    }

    private static Optional<IndexedType> typeAt(CodebaseIndex index, String path) {
        return index.types().stream().filter(t -> t.path().equals(path)).findFirst();
    }

    private static ComponentType typeOf(CodebaseIndex index, String path, boolean indexed) {
        Optional<IndexedType> type = typeAt(index, path);
        if (type.isPresent()) {
            return type.get().kind().componentType();
        }
        if (indexed || path.toLowerCase(Locale.ROOT).endsWith(".sql") || path.toLowerCase(Locale.ROOT).endsWith(".yml")
                || path.toLowerCase(Locale.ROOT).endsWith(".yaml")) {
            return ComponentType.RESOURCE;
        }
        return ComponentType.OTHER;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String label(String name) {
        return name.isBlank() ? "(unnamed)" : name;
    }

    private static String format(double confidence) {
        return String.format(Locale.ROOT, "%.2f", confidence);
    }
}
